# research/calc: parametric decode/prefill roofline calculator (theory agent TH).

Run:
  python research/calc/main.py --preset vivo-x200-fe --model qwen3-4b --quant q4km --ctx 2048
Tests:
  python research/calc/test_calc.py
