using System;
using System.Windows;
using System.Windows.Media;
using Microsoft.Win32;

namespace PocketForgeDesktop;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        Resources.MergedDictionaries.Add(BuildTheme(ResolveLight(TokenStore.Load().Theme)));

        // Last-resort guard: unhandled exceptions must not crash the app
        // silently; surface them and keep the UI alive.
        DispatcherUnhandledException += (s, args) =>
        {
            MessageBox.Show(args.Exception.Message, "Unexpected error",
                MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };

        base.OnStartup(e);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try
        {
            // kill the draft llama-server child process, if any
            TurboService.Shared?.Shutdown();
        }
        catch (Exception)
        {
        }
        base.OnExit(e);
    }

    // Live theme switch; mode is "system" | "dark" | "light".
    public static void ApplyTheme(string mode)
    {
        var app = Current;
        var d = BuildTheme(ResolveLight(mode));
        if (app.Resources.MergedDictionaries.Count > 0)
        {
            app.Resources.MergedDictionaries[0] = d;
        }
        else
        {
            app.Resources.MergedDictionaries.Add(d);
        }
    }

    private static bool ResolveLight(string? mode)
    {
        if (mode == "dark") return false;
        if (mode == "light") return true;
        return IsSystemLightTheme();
    }

    private static bool IsSystemLightTheme()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            if (key?.GetValue("AppsUseLightTheme") is int v)
            {
                return v != 0;
            }
        }
        catch (Exception)
        {
            // fall through to light default
        }
        return true;
    }

    private static ResourceDictionary BuildTheme(bool light)
    {
        var d = new ResourceDictionary();
        Set(d, "BgBrush", light ? "#F5F5F5" : "#1D1F22");
        Set(d, "PanelBrush", light ? "#FFFFFF" : "#26282C");
        Set(d, "CardBrush", light ? "#FFFFFF" : "#2F3136");
        Set(d, "HoverBrush", light ? "#E8E8E8" : "#3A3D42");
        Set(d, "PressedBrush", light ? "#DCDCDC" : "#46494F");
        Set(d, "BorderBrush", light ? "#DADCE0" : "#3C4043");
        Set(d, "TextBrush", light ? "#202124" : "#E8EAED");
        Set(d, "SubBrush", light ? "#5F6368" : "#9AA0A6");
        Set(d, "AccentBrush", light ? "#1A73E8" : "#8AB4F8");
        Set(d, "UserBubbleBrush", light ? "#D7E3FF" : "#2C4A78");
        Set(d, "AssistantBubbleBrush", light ? "#EDEFF1" : "#33363B");
        Set(d, "OkBrush", light ? "#1E8E3E" : "#34A853");
        Set(d, "ErrorBrush", light ? "#D93025" : "#F28B82");
        Set(d, "WarnBrush", light ? "#B26A00" : "#FDD663");
        return d;
    }

    private static void Set(ResourceDictionary d, string key, string hex)
    {
        var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
        brush.Freeze();
        d[key] = brush;
    }
}
