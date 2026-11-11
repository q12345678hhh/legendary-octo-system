using WorkAssistant.Device.Checks;

// 默认跑离线自检；--live 走真实 API，按阶段取证（enroll → crash → recover → revoke）
if (args.Contains("--live"))
{
    if (!LiveOptions.TryParse(args, out var options, out var error) || options is null)
    {
        Console.WriteLine($"INVALID {error}");
        return 2;
    }

    return await LiveChecks.RunAsync(options);
}

// 取证脚本用它在 var/ 下生成本地签名密钥，避免依赖 openssl 或提交私钥
if (args.Contains("--generate-keys"))
{
    var index = Array.IndexOf(args, "--generate-keys");
    if (index + 1 >= args.Length)
    {
        Console.WriteLine("INVALID --generate-keys 需要输出目录");
        return 2;
    }

    return KeyGenerator.Run(args[index + 1]);
}

return OfflineChecks.Run();
