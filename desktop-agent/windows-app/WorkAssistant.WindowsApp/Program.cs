namespace WorkAssistant.WindowsApp;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--self-check"))
        {
            return SelfCheck.Run(args);
        }

        ApplicationConfiguration.Initialize();
        using var context = new TrayApplicationContext(AgentSettings.FromEnvironment());
        Application.Run(context);
        return 0;
    }
}
