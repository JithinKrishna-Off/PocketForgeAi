namespace PocketForge.SelfTest;

internal static class Program
{
    private static async Task<int> Main()
    {
        Console.WriteLine("== PocketForge SelfTest ==");
        Console.WriteLine();

        string? mockExe = LocateMockPhoneExe();
        if (mockExe is null)
        {
            Result.SkipLine("mock-exe-locator", "MockPhone.exe not built; run: dotnet build tools/MockPhone/MockPhone.csproj");
        }

        int basePort = 17851 + Random.Shared.Next(0, 600);

        // defensive: if the exe vanished (clean) between locate and use, fall back to skip
        string? resolvedExe = mockExe is not null && File.Exists(mockExe) ? mockExe : null;

        var a = new PartA(resolvedExe, basePort);
        await a.Run();

        // app sources (Models/ChatApi/IModelClientAdapter) are compile-linked into this
        // csproj and McpTools is a ProjectReference, so only the mock exe is a runtime gate
        if (resolvedExe is not null)
            await PartB.Run(resolvedExe, basePort + 700);
        else
            Result.SkipLine("PartB", "no MockPhone.exe; run: dotnet build tools/MockPhone/MockPhone.csproj");
        await PartC.Run();

        Console.WriteLine();
        Console.WriteLine($"== Summary: {Result.Pass} passed, {Result.Fail} failed, {Result.Skip} skipped ==");
        return Result.Fail > 255 ? 255 : Result.Fail;
    }

    // walks up from the test bin dir to the repo root and finds the MockPhone exe;
    // triggers a build if missing so SelfTest can be run standalone
    private static string? LocateMockPhoneExe()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        for (int i = 0; i < 6 && dir is not null; i++)
        {
            string candidate = Path.Combine(dir.FullName, "tools", "MockPhone", "MockPhone.csproj");
            if (File.Exists(candidate))
                return FindExe(dir.FullName);
            dir = dir.Parent;
        }
        return null;
    }

    private static string? FindExe(string repoRoot)
    {
        string binDir = Path.Combine(repoRoot, "tools", "MockPhone", "bin");
        string? found = FindBuiltExe(binDir);
        if (found is not null)
            return found;

        Console.WriteLine("MockPhone.exe not found, building it...");
        string csproj = Path.Combine(repoRoot, "tools", "MockPhone", "MockPhone.csproj");
        var psi = new System.Diagnostics.ProcessStartInfo("dotnet", $"build \"{csproj}\" -v q --nologo")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        using var p = System.Diagnostics.Process.Start(psi);
        if (p is null) return null;
        if (!p.WaitForExit(180_000)) { try { p.Kill(); } catch { } return null; }
        if (p.ExitCode != 0)
        {
            Console.WriteLine(p.StandardOutput.ReadToEnd());
            Console.WriteLine(p.StandardError.ReadToEnd());
            return null;
        }
        return FindBuiltExe(binDir);
    }

    private static string? FindBuiltExe(string binDir)
    {
        if (!Directory.Exists(binDir)) return null;
        return Directory.EnumerateFiles(binDir, "MockPhone.exe", SearchOption.AllDirectories).FirstOrDefault();
    }
}

internal static class Result
{
    internal static int Pass, Fail, Skip;

    internal static void PassLine(string name) { Pass++; Console.WriteLine($"PASS: {name}"); }
    internal static void FailLine(string name, string why) { Fail++; Console.WriteLine($"FAIL: {name} ({why})"); }
    internal static void SkipLine(string name, string why) { Skip++; Console.WriteLine($"SKIP: {name} ({why})"); }

    internal static async Task<bool> Run(string name, Func<Task> body)
    {
        try
        {
            await body();
            PassLine(name);
            return true;
        }
        catch (Exception e)
        {
            FailLine(name, e.Message);
            return false;
        }
    }
}
