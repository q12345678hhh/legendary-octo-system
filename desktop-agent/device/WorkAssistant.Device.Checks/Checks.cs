namespace WorkAssistant.Device.Checks;

/// <summary>极简断言收集器：输出 PASS/FAIL 行并汇总退出码，供 CI 与证据脚本直接消费。</summary>
internal sealed class Checks
{
    private readonly string scope;
    private readonly List<string> failures = new();

    public Checks(string scope)
    {
        this.scope = scope;
    }

    public int Passed { get; private set; }

    public void Check(string name, Func<bool> assertion)
    {
        try
        {
            if (assertion())
            {
                Pass(name);
                return;
            }
        }
        catch (Exception error)
        {
            Fail(name, $"意外异常: {error.GetType().Name}: {error.Message}");
            return;
        }

        Fail(name, "断言为 false");
    }

    public void Throws<TException>(string name, Action action) where TException : Exception
    {
        try
        {
            action();
        }
        catch (TException)
        {
            Pass(name);
            return;
        }
        catch (Exception error)
        {
            Fail(name, $"期望 {typeof(TException).Name}，实际 {error.GetType().Name}");
            return;
        }

        Fail(name, $"期望抛出 {typeof(TException).Name}");
    }

    public int Report()
    {
        if (failures.Count == 0)
        {
            Console.WriteLine($"{scope}: 全部通过（{Passed} 项）");
            return 0;
        }

        Console.WriteLine($"{scope}: 通过 {Passed} 项，失败 {failures.Count} 项: {string.Join(", ", failures)}");
        return 1;
    }

    private void Pass(string name)
    {
        Passed++;
        Console.WriteLine($"PASS {scope}/{name}");
    }

    private void Fail(string name, string reason)
    {
        Console.WriteLine($"FAIL {scope}/{name} -> {reason}");
        failures.Add(name);
    }
}
