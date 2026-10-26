// pf_plan.cpp - JSON (de)serialisation for the PF plan v0 contract.
// Self-contained minimal recursive-descent parser/writer: no external JSON
// library is needed on any target (host MSVC/clang/gcc or NDK arm64).
#include "pf_plan.h"

#include <algorithm>
#include <cstdlib>
#include <map>
#include <sstream>
#include <cctype>

namespace pf {

namespace {

// Parses only what the contract needs: nested objects of string/int keys,
// string, number, bool, null values. Returns false with a human-readable error.
class MiniJsonParser {
public:
    MiniJsonParser(const std::string & s) : s_(s), i_(0) {}

    bool parse(std::map<std::string, std::string> * out, std::string * err) {
        skip_ws();
        if (!parse_object("", out)) {
            if (err && err->empty()) { *err = "malformed JSON near offset " + std::to_string(i_); }
            return false;
        }
        skip_ws();
        if (i_ != s_.size()) {
            if (err) { *err = "trailing data at offset " + std::to_string(i_); }
            return false;
        }
        return true;
    }

private:
    const std::string & s_;
    size_t i_;

    void skip_ws() {
        while (i_ < s_.size() && (s_[i_] == ' ' || s_[i_] == '\t' || s_[i_] == '\r' || s_[i_] == '\n')) { ++i_; }
    }
    bool peek(char c) const { return i_ < s_.size() && s_[i_] == c; }
    bool eat(char c) {
        if (peek(c)) { ++i_; return true; }
        return false;
    }

    bool parse_string(std::string * out) {
        if (!eat('"')) { return false; }
        out->clear();
        while (i_ < s_.size() && s_[i_] != '"') {
            if (s_[i_] == '\\' && i_ + 1 < s_.size()) {
                ++i_;
                const char c = s_[i_++];
                switch (c) {
                    case 'n': *out += '\n'; break;
                    case 't': *out += '\t'; break;
                    case 'r': *out += '\r'; break;
                    case '"': *out += '"'; break;
                    case '\\': *out += '\\'; break;
                    default: *out += c; break; // \uXXXX: plan fields never need it
                }
            } else {
                *out += s_[i_++];
            }
        }
        return eat('"');
    }

    // Arrays are never part of the plan contract's scalar fields; when one
    // appears (e.g. facts.cores inside the plan+facts wrapper) skip the
    // balanced group wholesale and store the raw text.
    bool skip_array(std::string * out) {
        const size_t begin = i_;
        if (!eat('[')) { return false; }
        int depth = 1;
        bool instr = false, esc = false;
        while (i_ < s_.size() && depth > 0) {
            const char c = s_[i_];
            if (instr) {
                if (esc) { esc = false; }
                else if (c == '\\') { esc = true; }
                else if (c == '"') { instr = false; }
            } else if (c == '"') { instr = true; }
            else if (c == '[') { ++depth; }
            else if (c == ']') { --depth; }
            ++i_;
        }
        if (depth != 0) { return false; }
        *out = s_.substr(begin, i_ - begin);
        return true;
    }

    bool parse_scalar(std::string * out) {
        skip_ws();
        if (peek('"')) { return parse_string(out); }
        if (peek('[')) { return skip_array(out); }
        const size_t begin = i_;
        while (i_ < s_.size() && s_[i_] != ',' && s_[i_] != '}' && s_[i_] != ']') { ++i_; }
        *out = s_.substr(begin, i_ - begin);
        size_t j = out->size();
        while (j > 0 && (isspace((unsigned char) (*out)[j - 1]))) { --j; }
        out->resize(j);
        size_t k = 0;
        while (k < out->size() && (unsigned char)(*out)[k] <= 0x20) { ++k; }
        if (k) { out->erase(0, k); }
        return true;
    }

    bool parse_object(const std::string & prefix, std::map<std::string, std::string> * out) {
        skip_ws();
        if (!eat('{')) { return false; }
        skip_ws();
        if (eat('}')) { return true; }
        while (true) {
            skip_ws();
            std::string key;
            if (!parse_string(&key)) { return false; }
            skip_ws();
            if (!eat(':')) { return false; }
            const std::string path = prefix.empty() ? key : prefix + "." + key;
            skip_ws();
            if (peek('{')) {
                if (!parse_object(path, out)) { return false; }
            } else {
                std::string value;
                if (!parse_scalar(&value)) { return false; }
                (*out)[path] = value;
            }
            skip_ws();
            if (eat(',')) { continue; }
            if (eat('}')) { return true; }
            return false;
        }
    }
};

} // namespace

bool parse_plan(const std::string & json, PfPlan * out, std::string * err) {
    std::map<std::string, std::string> m;
    if (!MiniJsonParser(json).parse(&m, err)) { return false; }
    // Accept both a bare plan object and the plan+facts wrapper produced by
    // pfPlansJson(): if keys only exist under "currentPlan.", re-key them.
    if (!m.count("pfPlanVersion") && m.count("currentPlan.pfPlanVersion")) {
        std::map<std::string, std::string> flat;
        for (const auto & kv : m) {
            std::string key = kv.first;
            const std::string strip = key.substr(0, 12) == "currentPlan." ? key.erase(0, 12) : key;
            if (!strip.empty() && strip.find('.') != 0) { flat[strip] = kv.second; }
        }
        m = std::move(flat);
    }
    if (!m.count("pfPlanVersion")) {
        if (err) { *err = "missing pfPlanVersion"; }
        return false;
    }
    PfPlan p = PfPlan();
    auto get_int = [&](const char * key, int * dest) {
        auto it = m.find(key);
        if (it == m.end()) { return false; }
        *dest = atoi(it->second.c_str());
        return true;
    };
    auto get_ll = [&](const char * key, long long * dest) {
        auto it = m.find(key);
        if (it == m.end()) { return false; }
        *dest = atoll(it->second.c_str());
        return true;
    };
    auto get_str = [&](const char * key, std::string * dest) {
        auto it = m.find(key);
        if (it == m.end()) { return false; }
        *dest = it->second;
        return true;
    };
    get_int("pfPlanVersion", &p.planVersion);
    get_str("cpu.cpuVariant", &p.cpuVariant);
    get_int("cpu.nThreads", &p.nThreads);
    get_int("cpu.nThreadsBatch", &p.nThreadsBatch);
    get_str("kv.typeK", &p.kvTypeK);
    get_str("kv.typeV", &p.kvTypeV);
    get_str("kv.flashAttn", &p.flashAttn);
    get_int("batch.nBatch", &p.nBatch);
    get_int("batch.nUbatch", &p.nUbatch);
    get_int("split.nGpuLayers", &p.nGpuLayers);
    get_str("split.loadMode", &p.loadMode);
    get_str("steer.spec", &p.steerSpec);
    get_ll("heap.maxHeapBytes", &p.maxHeapBytes);

    auto valid_enum = [](const std::string & v, const char * const * opts, size_t n) {
        for (size_t i = 0; i < n; ++i) { if (v == opts[i]) { return true; } }
        return false;
    };
    static const char * k_kv[] = {"f16", "q8_0", "q4_0"};
    static const char * k_fa[] = {"auto", "on", "off"};
    static const char * k_lm[] = {"auto", "mmap", "mlock", "mmap_mlock", "direct_io", "none"};
    if (!valid_enum(p.kvTypeK, k_kv, 3)) { if (err) { *err = "kv.typeK not in {f16,q8_0,q4_0}"; } return false; }
    if (!valid_enum(p.kvTypeV, k_kv, 3)) { if (err) { *err = "kv.typeV not in {f16,q8_0,q4_0}"; } return false; }
    if (!valid_enum(p.flashAttn, k_fa, 3)) { if (err) { *err = "kv.flashAttn not in {auto,on,off}"; } return false; }
    if (!valid_enum(p.loadMode, k_lm, 6)) { if (err) { *err = "split.loadMode not in {auto,mmap,mlock,mmap_mlock,direct_io,none}"; } return false; }
    if (p.nThreads != -1 && (p.nThreads < 1 || p.nThreads > 64)) { if (err) { *err = "cpu.nThreads out of range"; } return false; }
    if (p.nThreadsBatch != -1 && (p.nThreadsBatch < 1 || p.nThreadsBatch > 64)) { if (err) { *err = "cpu.nThreadsBatch out of range"; } return false; }
    if (p.nBatch != -1 && (p.nBatch < 16 || p.nBatch > 4096)) { if (err) { *err = "batch.nBatch out of range"; } return false; }
    if (p.nUbatch != -1 && p.nUbatch < 16) { if (err) { *err = "batch.nUbatch out of range"; } return false; }
    if (p.nUbatch != -1 && p.nBatch != -1 && p.nUbatch > p.nBatch) { if (err) { *err = "batch.nUbatch must be <= batch.nBatch"; } return false; }
    if (p.nGpuLayers != -2 && p.nGpuLayers < -1) { if (err) { *err = "split.nGpuLayers must be >= -1 or -2 (defer)"; } return false; }
    if (out) { *out = p; }
    return true;
}

std::string serialize_plan(const PfPlan & plan) {
    auto json_escape = [](const std::string & s) {
        std::string out;
        for (char c : s) {
            if (c == '"' || c == '\\') { out += '\\'; out += c; }
            else { out += c; }
        }
        return out;
    };
    std::ostringstream o;
    o << "{"
      << "\"pfPlanVersion\":" << plan.planVersion << ","
      << "\"cpu\":{\"cpuVariant\":\"" << plan.cpuVariant << "\","
      << "\"nThreads\":" << plan.nThreads << ","
      << "\"nThreadsBatch\":" << plan.nThreadsBatch << "},"
      << "\"kv\":{\"typeK\":\"" << plan.kvTypeK << "\","
      << "\"typeV\":\"" << plan.kvTypeV << "\","
      << "\"flashAttn\":\"" << plan.flashAttn << "\"},"
      << "\"batch\":{\"nBatch\":" << plan.nBatch << ","
      << "\"nUbatch\":" << plan.nUbatch << "},"
      << "\"split\":{\"nGpuLayers\":" << plan.nGpuLayers << ","
      << "\"loadMode\":\"" << plan.loadMode << "\"},"
      << "\"steer\":{\"spec\":\"" << json_escape(plan.steerSpec) << "\"},"
      << "\"heap\":{\"maxHeapBytes\":" << plan.maxHeapBytes << "}"
      << "}";
    return o.str();
}

std::string serialize_facts(const PfDeviceFacts & f) {
    std::ostringstream cores;
    for (size_t i = 0; i < f.cores.size(); ++i) {
        if (i > 0) { cores << ","; }
        cores << "{\"id\":" << f.cores[i].id
              << ",\"minKhz\":" << f.cores[i].minKhz
              << ",\"maxKhz\":" << f.cores[i].maxKhz << "}";
    }
    std::ostringstream hex;
    hex << std::hex << f.hwcap;
    std::ostringstream hex2;
    hex2 << std::hex << f.hwcap2;
    std::ostringstream o;
    o << "{"
      << "\"backendName\":\"" << f.backendName << "\","
      << "\"nCoresOnline\":" << f.nCoresOnline << ","
      << "\"cores\":[" << cores.str() << "],"
      << "\"hwcap\":\"0x" << hex.str() << "\","
      << "\"hwcap2\":\"0x" << hex2.str() << "\","
      << "\"features\":{\"dotprod\":" << (f.hasDotprod ? "true" : "false")
      << ",\"fp16\":" << (f.hasFp16 ? "true" : "false")
      << ",\"i8mm\":" << (f.hasI8mm ? "true" : "false")
      << ",\"sve\":" << (f.hasSve ? "true" : "false")
      << ",\"sve2\":" << (f.hasSve2 ? "true" : "false")
      << ",\"sme\":" << (f.hasSme ? "true" : "false")
      << ",\"sme2\":" << (f.hasSme2 ? "true" : "false") << "},"
      << "\"vulkanOk\":" << (f.vulkanOk ? "true" : "false") << ","
      << "\"memAvailableBytes\":" << f.memAvailableBytes << ","
      << "\"factsValid\":" << (f.factsValid ? "true" : "false")
      << "}";
    return o.str();
}

std::string plan_and_facts_json(const PfPlan & plan, const PfDeviceFacts & facts) {
    std::ostringstream o;
    o << "{\"facts\":" << serialize_facts(facts)
      << ",\"currentPlan\":" << serialize_plan(plan) << "}";
    return o.str();
}

} // namespace pf
