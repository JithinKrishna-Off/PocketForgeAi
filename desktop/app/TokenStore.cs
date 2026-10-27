using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text.Json;

namespace PocketForgeDesktop;

public sealed class DesktopSettings
{
    public string Host { get; set; } = "127.0.0.1";
    public int Port { get; set; } = 8751;
    public string? TokenProtected { get; set; }
    public string? LastConnected { get; set; }
    public bool AgentMode { get; set; }
    public bool TurboEnabled { get; set; }
    public string? DraftExePath { get; set; }
    public string? DraftModelPath { get; set; }
    public int DraftPort { get; set; } = 8755;
    public int DraftK { get; set; } = 4;
    public List<string>? WorkspaceRoots { get; set; }
    public string Theme { get; set; } = "system";
}

// Settings + DPAPI-protected pairing token at %APPDATA%\PocketForgeDesktop\settings.json.
// The token is never logged or shown in the UI.
public static class TokenStore
{
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    public static string SettingsPath
    {
        get
        {
            string dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "PocketForgeDesktop");
            return Path.Combine(dir, "settings.json");
        }
    }

    public static DesktopSettings Load()
    {
        try
        {
            if (!File.Exists(SettingsPath))
            {
                return new DesktopSettings();
            }
            var s = JsonSerializer.Deserialize<DesktopSettings>(File.ReadAllText(SettingsPath), JsonOpts);
            return s ?? new DesktopSettings();
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            return new DesktopSettings();
        }
    }

    public static void Save(DesktopSettings settings)
    {
        try
        {
            string? dir = Path.GetDirectoryName(SettingsPath);
            if (dir != null)
            {
                Directory.CreateDirectory(dir);
            }
            File.WriteAllText(SettingsPath, JsonSerializer.Serialize(settings, JsonOpts));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // best effort: a failed save must not take the app down
        }
    }

    public static string Protect(string token)
    {
        byte[] data = System.Text.Encoding.UTF8.GetBytes(token);
        byte[] enc = ProtectedData.Protect(data, null, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(enc);
    }

    public static string? Unprotect(string? tokenProtected)
    {
        if (string.IsNullOrEmpty(tokenProtected))
        {
            return null;
        }
        try
        {
            byte[] data = Convert.FromBase64String(tokenProtected);
            byte[] dec = ProtectedData.Unprotect(data, null, DataProtectionScope.CurrentUser);
            return System.Text.Encoding.UTF8.GetString(dec);
        }
        catch (Exception ex) when (ex is CryptographicException or FormatException)
        {
            // stale/unreadable token from another user profile or a corrupt file
            return null;
        }
    }
}
