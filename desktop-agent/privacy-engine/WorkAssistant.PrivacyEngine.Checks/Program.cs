using WorkAssistant.PrivacyEngine;
using WorkAssistant.PrivacyEngine.Checks;

// --live 走真实 Windows 桌面采样，由人工在测试机上执行并归档证据
if (args.Contains("--live"))
{
    return LiveWindowProbe.Run();
}

var failures = new List<string>();
var passed = 0;

void Check(string name, Func<bool> assertion)
{
    try
    {
        if (assertion())
        {
            passed++;
            Console.WriteLine($"PASS {name}");
            return;
        }
    }
    catch (Exception error)
    {
        Console.WriteLine($"FAIL {name} -> 意外异常: {error.GetType().Name}: {error.Message}");
        failures.Add(name);
        return;
    }

    Console.WriteLine($"FAIL {name}");
    failures.Add(name);
}

var code = new WindowSnapshot("Code", "Program.cs - platform", @"C:\Program Files\Microsoft VS Code\Code.exe");
var wechat = new WindowSnapshot("WeChat", "微信", null);
var qq = new WindowSnapshot("QQ", "QQ", null);
var anonymous = new WindowSnapshot(null, null, null);

// 1. 未开始采集时，任何窗口都不采集
var session = new CaptureSession();
Check("未开始时采集量为 0", () => session.EvaluateCapture(code).Excluded);

// 2. 显式开始后，普通工作应用允许采集
session.Start();
Check("开始后允许采集普通工作应用", () => !session.EvaluateCapture(code).Excluded);

// 3. 微信与 QQ 在内容读取前被强制排除
Check("微信被强制排除", () => session.EvaluateCapture(wechat).Excluded);
Check("QQ 被强制排除", () => session.EvaluateCapture(qq).Excluded);

// 4. 无法识别进程时失败即拒绝
Check("未知进程按排除处理", () => session.EvaluateCapture(anonymous).Excluded);

// 5. 暂停与私人模式下采集量为 0
session.Pause();
Check("暂停时不采集", () => session.EvaluateCapture(code).Excluded);

session.Resume();
session.EnterPrivateMode();
Check("私人模式不采集", () => session.EvaluateCapture(code).Excluded);
Check("私人模式下提示必须可见", () => session.IsIndicatorRequired);

session.ExitPrivateMode();
Check("退出私人模式回到暂停", () => session.State == CaptureState.Paused);

// 6. 非法状态迁移必须抛出，不能静默进入采集
var fresh = new CaptureSession();
Check("未开始时暂停被拒绝", () => Throws(() => fresh.Pause()));
Check("未开始时进入私人模式被拒绝", () => Throws(() => fresh.EnterPrivateMode()));
Check("停止后重复停止被拒绝", () => Throws(() => fresh.Stop()));

// 7. 停止后提示消失
session.Stop();
Check("停止后不再显示采集提示", () => !session.IsIndicatorRequired);
Check("停止后不采集", () => session.EvaluateCapture(code).Excluded);

Console.WriteLine($"检查通过 {passed} 项，失败 {failures.Count} 项");
if (failures.Count > 0)
{
    Console.WriteLine("失败项: " + string.Join(", ", failures));
    return 1;
}

return 0;

static bool Throws(Action action)
{
    try
    {
        action();
        return false;
    }
    catch (InvalidOperationException)
    {
        return true;
    }
}
