namespace WorkAssistant.WindowsApp;

/// <summary>运行配置全部来自环境变量；没有配置 API 与租户时以本地模式启动，只做状态展示，不采集也不上传。</summary>
internal sealed record AgentSettings(string? Api, string? Tenant, string? Subject, string Workdir)
{
    public bool IsRegistered =>
        !string.IsNullOrWhiteSpace(Api) && !string.IsNullOrWhiteSpace(Tenant);

    public static AgentSettings FromEnvironment()
    {
        return new AgentSettings(
            Environment.GetEnvironmentVariable("WORK_ASSISTANT_API"),
            Environment.GetEnvironmentVariable("WORK_ASSISTANT_TENANT"),
            Environment.GetEnvironmentVariable("WORK_ASSISTANT_SUBJECT"),
            Environment.GetEnvironmentVariable("WORK_ASSISTANT_WORKDIR")
            ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WorkAssistant"));
    }

    /// <summary>自检与截图取证使用的本地配置：不连服务器，也不读写用户真实目录。</summary>
    public static AgentSettings LocalOnly(string workdir) => new(null, null, null, workdir);
}
