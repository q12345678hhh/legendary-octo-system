using WorkAssistant.PrivacyEngine;

namespace WorkAssistant.Core;

/// <summary>待上传片段：只有元数据，不含原始窗口内容。</summary>
public sealed record SegmentDraft(
    Guid ClientSegmentId,
    DateTimeOffset CapturedFrom,
    DateTimeOffset CapturedTo,
    string SourceType,
    string? Summary,
    int PolicyVersion);

/// <summary>
/// 采集门禁：未开始、已暂停、私人模式或策略关闭上传时一律不产生片段。
/// 这是「员工未开始或已暂停时采集量为 0」的第一道（也是客户端唯一一道）入口。
/// </summary>
public static class CaptureGate
{
    public static bool TryDraft(CaptureSession session, PolicySnapshot policy,
        DateTimeOffset capturedFrom, DateTimeOffset capturedTo, string? summary,
        out SegmentDraft? draft)
    {
        ArgumentNullException.ThrowIfNull(session);
        ArgumentNullException.ThrowIfNull(policy);

        draft = null;
        if (session.State != CaptureState.Capturing || !policy.UploadEnabled)
        {
            return false;
        }

        if (capturedTo <= capturedFrom)
        {
            throw new ArgumentException("片段结束时间必须晚于开始时间");
        }

        draft = new SegmentDraft(Guid.NewGuid(), capturedFrom, capturedTo, "window",
            summary, policy.PolicyVersion);
        return true;
    }
}
