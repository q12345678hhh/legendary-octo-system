namespace WorkAssistant.WindowsApp;

/// <summary>
/// 置顶采集提示窗。无边框、不抢焦点、不显示在任务栏；
/// 用户无法通过关闭按钮或 Alt+F4 隐藏，只有应用退出时才允许关闭。
/// </summary>
internal sealed class IndicatorForm : Form
{
    private readonly Label label;

    public IndicatorForm()
    {
        Text = "工作助手采集提示";
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        BackColor = Color.FromArgb(28, 28, 30);
        Width = 380;
        Height = 64;

        label = new Label
        {
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            ForeColor = Color.White,
            Font = new Font("Microsoft YaHei UI", 9F),
            AutoEllipsis = true,
        };
        Controls.Add(label);

        var area = Screen.PrimaryScreen?.WorkingArea ?? new Rectangle(0, 0, 1280, 720);
        Location = new Point(area.Right - Width - 24, area.Bottom - Height - 24);
    }

    public bool AllowClose { get; set; }

    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            const int WsExNoActivate = 0x08000000;
            var parameters = base.CreateParams;
            parameters.ExStyle |= WsExNoActivate;
            return parameters;
        }
    }

    public void SetText(string text)
    {
        label.Text = text;
        BringToFront();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!AllowClose)
        {
            e.Cancel = true;
        }

        base.OnFormClosing(e);
    }
}
