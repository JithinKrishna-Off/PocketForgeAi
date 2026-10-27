using System;
using System.Collections.ObjectModel;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace PocketForgeDesktop;

public partial class MainWindow : Window
{
    private PhoneChatClient? _client;
    private DesktopSettings _settings = new();
    private CancellationTokenSource? _turnCts;
    private bool _connected;
    private bool _sending;
    private bool _resetPending;
    private readonly TurboService _turbo = new();

    public ObservableCollection<ChatItem> Messages { get; } = new();
    public ObservableCollection<LoopLineItem> LoopItems { get; } = new();

    public MainWindow()
    {
        InitializeComponent();
        DataContext = this;

        _settings = TokenStore.Load();
        HostBox.Text = _settings.Host;
        PortBox.Text = _settings.Port.ToString();
        AgentModeCheckBox.IsChecked = _settings.AgentMode;
        TurboCheckBox.IsChecked = _settings.TurboEnabled; // fires Turbo_Changed
        ThemeButton.Content = ThemeLabel(_settings.Theme);
        SetStatus("Disconnected", null);
    }

    private void Theme_Click(object sender, RoutedEventArgs e)
    {
        var next = _settings.Theme switch { "system" => "dark", "dark" => "light", _ => "system" };
        _settings.Theme = next;
        TokenStore.Save(_settings);
        App.ApplyTheme(next);
        ThemeButton.Content = ThemeLabel(next);
    }

    private static string ThemeLabel(string? mode) => mode switch
    {
        "dark" => "Theme: Dark",
        "light" => "Theme: Light",
        _ => "Theme: Auto",
    };

    private static Brush? DotBrush(string? key) =>
        key != null ? Application.Current.TryFindResource(key) as Brush : null;

    private void SetStatus(string text, string? dotKey)
    {
        StatusText.Text = text;
        StatusDot.Fill = DotBrush(dotKey) ?? Brushes.Gray;
    }

    private void ShowError(string message) => ErrorLine.Text = message;

    private void ScrollChat() => ChatScroll.ScrollToEnd();

    private void SetBusyUi(bool busy)
    {
        SendButton.IsEnabled = !busy;
        StopButton.IsEnabled = busy;
        NewSessionButton.IsEnabled = !busy;
        ConnectButton.IsEnabled = !busy;
        DisconnectButton.IsEnabled = _connected;
    }

    // ---- connection -------------------------------------------------------

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            await ConnectAsync();
        }
        catch (Exception ex)
        {
            ShowError("Unexpected: " + ex.Message);
            SetStatus("Error", "ErrorBrush");
        }
    }

    private async Task ConnectAsync()
    {
        ShowError("");
        if (_sending)
        {
            ShowError("Wait for the current turn to finish.");
            return;
        }

        string host = HostBox.Text.Trim();
        if (host.Length == 0)
        {
            ShowError("Host is empty.");
            return;
        }
        if (!int.TryParse(PortBox.Text.Trim(), out int port) || port < 1 || port > 65535)
        {
            ShowError("Invalid port.");
            return;
        }

        _connected = false;
        var old = _client;
        _client = new PhoneChatClient(host, port);
        old?.Dispose();
        SetStatus("Connecting...", "WarnBrush");
        SetBusyUi(false);
        ConnectButton.IsEnabled = false;

        try
        {
            var health = await _client.ProbeAsync(host, port, CancellationToken.None);
            PfApiText.Text = health.Ok ? "pf_api: " + health.Api : "pf_api: unreachable";
            if (!health.Ok)
            {
                throw new PhoneApiException(
                    "No PocketForge server reachable at " + host + ":" + port +
                    ". Start the phone server (Settings -> Desktop server), then retry. " +
                    "For USB use: adb reverse tcp:8751 tcp:8751.", 0);
            }
            if (string.IsNullOrEmpty(health.Api) || !health.Api.StartsWith("v1", StringComparison.OrdinalIgnoreCase))
            {
                throw new PhoneApiException(
                    "Refusing to connect: server reports pf_api '" + health.Api +
                    "' but this client speaks v1.", 0);
            }

            var token = TokenStore.Unprotect(_settings.TokenProtected);
            if (token != null)
            {
                _client.SetToken(token);
            }

            try
            {
                await RefreshStatusCoreAsync();
            }
            catch (UnauthorizedPhoneException)
            {
                if (!await PairFlowAsync())
                {
                    throw new PhoneApiException("Pairing cancelled; not connected.", 0);
                }
                await RefreshStatusCoreAsync();
            }

            _connected = true;
            _settings.Host = host;
            _settings.Port = port;
            _settings.LastConnected = DateTime.UtcNow.ToString("o");
            TokenStore.Save(_settings);
            DisconnectButton.IsEnabled = true;
            SetStatus("Connected", "OkBrush");
        }
        catch (PhoneApiException pex)
        {
            ShowError(pex.Message);
            if (!_connected)
            {
                SetStatus("Error", "ErrorBrush");
            }
        }
        catch (HttpRequestException hex)
        {
            ShowError("Connection failed: " + hex.Message);
            SetStatus("Error", "ErrorBrush");
        }
        catch (Exception ex)
        {
            ShowError("Unexpected: " + ex.Message);
            SetStatus("Error", "ErrorBrush");
        }
        finally
        {
            SetBusyUi(false);
        }
    }

    private void Disconnect_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            _turnCts?.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
        var old = _client;
        _client = null;
        try
        {
            old?.Dispose();
        }
        catch (ObjectDisposedException)
        {
        }
        _connected = false;
        SetStatus("Disconnected", null);
        ModelInfoText.Text = "Not connected.";
        SetBusyUi(false);
        DisconnectButton.IsEnabled = false;
    }

    private async void RefreshStatus_Click(object sender, RoutedEventArgs e)
    {
        if (_client == null || !_connected)
        {
            ShowError("Not connected.");
            return;
        }
        try
        {
            await RefreshStatusCoreAsync();
        }
        catch (Exception ex)
        {
            ShowError("Status refresh failed: " + ex.Message);
        }
    }

    private async Task RefreshStatusCoreAsync()
    {
        if (_client == null)
        {
            return;
        }
        var status = await _client.GetStatusAsync(CancellationToken.None);
        if (!status.Server)
        {
            ShowError("The phone reports the desktop server section is off (enable Settings -> Desktop server).");
            return;
        }
        string modelText;
        if (status.Model is { } m && m.Loaded)
        {
            modelText = (m.Name ?? "model") +
                " | ctx " + m.NCtx +
                " | threads " + m.NThreads +
                " | backend " + (m.Backend ?? "-");
        }
        else
        {
            modelText = "No model loaded (requests will fail with 503).";
        }
        ModelInfoText.Text = modelText;
        SetStatus("Connected: " + status.State + (status.Busy ? " (busy)" : ""), status.Busy ? "WarnBrush" : "OkBrush");
    }

    private async Task<bool> PairFlowAsync()
    {
        while (true)
        {
            var dlg = new PairDialog { Owner = this };
            if (dlg.ShowDialog() != true)
            {
                return false;
            }
            try
            {
                var token = await _client!.PairAsync(dlg.AcceptedCode, CancellationToken.None);
                _client.SetToken(token);
                _settings.TokenProtected = TokenStore.Protect(token);
                TokenStore.Save(_settings);
                return true;
            }
            catch (PhoneApiException pex) when (pex.StatusCode == 429)
            {
                dlg.ShowError("Too many attempts (max 5 per minute). Wait a moment, then retry.");
            }
            catch (PhoneApiException pex)
            {
                dlg.ShowError("Pairing failed (" + pex.StatusCode + "): " + pex.Message);
            }
            catch (HttpRequestException hex)
            {
                dlg.ShowError("Cannot reach server: " + hex.Message);
            }
        }
    }

    // ---- chat -------------------------------------------------------------

    private async void Send_Click(object sender, RoutedEventArgs e) => await SendAsyncSafe();

    private async Task<bool> SendAsyncSafe()
    {
        try
        {
            await SendAsync();
            return true;
        }
        catch (Exception ex)
        {
            ShowError("Unexpected: " + ex.Message);
            return false;
        }
        finally
        {
            EndTurnUi();
        }
    }

    private async Task SendAsync()
    {
        string text = InputBox.Text;
        if (string.IsNullOrWhiteSpace(text))
        {
            return;
        }
        if (!_connected || _client == null)
        {
            ShowError("Not connected. Press Connect first.");
            return;
        }
        if (_sending)
        {
            return;
        }

        _sending = true;
        SetBusyUi(true);
        text = text.TrimEnd('\r', '\n');
        InputBox.Clear();
        ShowError("");

        bool reset = _resetPending;
        _resetPending = false;

        Messages.Add(new ChatItem { Role = "user", Content = text });
        var live = new ChatItem { Role = "assistant", Content = "" };
        Messages.Add(live);
        ScrollChat();

        _turnCts = new CancellationTokenSource();
        CancellationToken ct = _turnCts.Token;

        await RunTurnAsync(live, text, reset, ct);
    }

    private async Task RunTurnAsync(ChatItem live, string text, bool reset, CancellationToken ct)
    {
        // a failed turbo turn falls back to normal; a partially consumed
        // phone-side turbo turn forces a fresh session for that rewrite
        bool normalReset = reset;
        try
        {
            if (TurboCheckBox.IsChecked == true && AgentModeCheckBox.IsChecked != true)
            {
                try
                {
                    await RunTurboTurnAsync(live, text, reset, ct);
                    ShowError("");
                    return;
                }
                catch (TurboFallbackException tf)
                {
                    if (tf.TurnStarted)
                    {
                        normalReset = true;
                    }
                    SetTurboStatus(tf.Kind == TurboFailureKind.Unavailable ? "unavailable" : "error", tf.Message);
                    ShowError((tf.Kind == TurboFailureKind.Unavailable
                        ? "Turbo unavailable: "
                        : "Turbo error: ") + tf.Message + " - running this turn in normal mode.");
                }
            }

            if (AgentModeCheckBox.IsChecked == true)
            {
                _client!.NextTurnResetsSession = reset;
                await foreach (var ev in AgentLoopHost.RunAsync(text, _client, ct))
                {
                    if (ev.Kind == "delta")
                    {
                        AppendToBubble(live, ev.Text);
                    }
                    else
                    {
                        AddLoopLine(ev.Kind, ev.Text, ev.Detail);
                    }
                }
            }
            else
            {
                var messages = new ChatMessage[] { new("user", text) };
                var options = new ChatOptions(Stream: true, MaxTokens: 2048, Stop: null, ResetSession: normalReset);
                await foreach (var d in _client!.StreamChatAsync(messages, options, ct))
                {
                    if (d.Content != null)
                    {
                        AppendToBubble(live, d.Content);
                        ScrollChat();
                    }
                    if (d.Error != null)
                    {
                        ShowError("Generation error: " + d.Error);
                    }
                }
            }
            ShowError("");
        }
        catch (UnauthorizedPhoneException)
        {
            if (!await PairFlowAsync())
            {
                ShowError("Auth failed and pairing was cancelled - press Connect to try again.");
                return;
            }
            await RefreshStatusCoreAsync();
            ShowError("Re-paired; send your message again.");
        }
        catch (BusyPhoneException)
        {
            ShowError("Phone is busy (a generation is already running, maybe from the app UI). Try again shortly.");
        }
        catch (ModelNotReadyException)
        {
            ShowError("Phone model not loaded (503). Load a model in the phone app.");
        }
        catch (PhoneApiException pex)
        {
            ShowError("Phone API error " + pex.StatusCode + ": " + pex.Message);
        }
        catch (ObjectDisposedException)
        {
            // client torn down mid-stream (disconnect while generating)
        }
        catch (HttpRequestException hex)
        {
            ShowError("Connection lost: " + hex.Message);
        }
        catch (OperationCanceledException)
        {
            // stopped by the user or the connection was cancelled
        }
        catch (Exception ex)
        {
            ShowError("Unexpected: " + ex.Message);
        }
    }

    private void EndTurnUi()
    {
        _sending = false;
        SetBusyUi(false);
        ScrollChat();
    }

    private void AppendToBubble(ChatItem live, string chunk)
    {
        live.Content += chunk;
        ScrollChat();
    }

    private void AddLoopLine(string kind, string text, string? detail)
    {
        LoopItems.Add(new LoopLineItem(DateTime.Now.ToString("HH:mm:ss"), kind, text, detail));
        if (LoopItems.Count > 300)
        {
            LoopItems.RemoveAt(0);
        }
        ScrollChat();
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (_turnCts != null)
        {
            try
            {
                _turnCts.Cancel();
            }
            catch (ObjectDisposedException)
            {
            }
        }
        if (_client != null)
        {
            try
            {
                using var cancelCts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
                await _client.CancelAsync(cancelCts.Token);
            }
            catch (Exception ex) when (ex is PhoneApiException or HttpRequestException
                or OperationCanceledException or ObjectDisposedException or TaskCanceledException)
            {
                // the local token already stops the UI stream even if cancel delivery fails
            }
        }
    }

    private void NewSession_Click(object sender, RoutedEventArgs e)
    {
        if (_sending)
        {
            ShowError("Wait for the current turn to finish.");
            return;
        }
        _resetPending = true;
        Messages.Clear();
        Messages.Add(new ChatItem
        {
            Role = "assistant",
            Content = "[new session: the next message starts a fresh phone-side conversation]"
        });
        ScrollChat();
        ShowError("");
    }

    private void InputBox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter)
        {
            return;
        }
        if (Keyboard.Modifiers.HasFlag(ModifierKeys.Shift))
        {
            return; // let the newline through (AcceptsReturn is on)
        }
        e.Handled = true;
        if (!_sending)
        {
            _ = SendAsyncSafe();
        }
    }

    private void AgentMode_Changed(object sender, RoutedEventArgs e)
    {
        _settings.AgentMode = AgentModeCheckBox.IsChecked == true;
        TokenStore.Save(_settings);
    }

    // ---- turbo (distributed speculative decoding) --------------------------

    private void Turbo_Changed(object sender, RoutedEventArgs e)
    {
        _settings.TurboEnabled = TurboCheckBox.IsChecked == true;
        TokenStore.Save(_settings);
        SetTurboStatus(_settings.TurboEnabled ? "on" : "normal", null);
        if (_settings.TurboEnabled)
        {
            _ = ValidateTurboNowAsync();
        }
    }

    // Precheck as early as possible so a broken setup shows up before a turn;
    // the first turbo turn re-runs the same checks.
    private async Task ValidateTurboNowAsync()
    {
        if (_client == null || !_connected || _sending)
        {
            return; // validated lazily on the first turbo turn / at turn time
        }
        SetTurboStatus("starting", null);
        try
        {
            await _turbo.WarmUpAsync(_client, TurboConfig.FromSettings(_settings), CancellationToken.None);
            SetTurboStatus("ready", null);
        }
        catch (TurboFallbackException tf)
        {
            SetTurboStatus(tf.Kind == TurboFailureKind.Unavailable ? "unavailable" : "error", tf.Message);
            ShowError((tf.Kind == TurboFailureKind.Unavailable
                ? "Turbo unavailable: "
                : "Turbo error: ") + tf.Message);
        }
        catch (Exception ex)
        {
            SetTurboStatus("error", ex.Message);
            ShowError("Turbo check failed: " + ex.Message);
        }
    }

    private async Task RunTurboTurnAsync(ChatItem live, string text, bool reset, CancellationToken ct)
    {
        SetTurboStatus("starting", null);
        await _turbo.RunTurnAsync(
            _client!,
            TurboConfig.FromSettings(_settings),
            new TurboTurnRequest(
                User: text,
                Conversation: BuildDraftConversation(reset, text),
                System: null,
                MaxTokens: 2048,
                ResetSession: reset),
            chunk => AppendToBubble(live, chunk),
            ct);
        SetTurboStatus("ready", null);
    }

    // Rendered conversation for the draft model: the full UI transcript unless
    // the session restarts (then only the new user text); UI placeholders and
    // the still-empty assistant bubble for this turn are excluded.
    private List<ChatMessage> BuildDraftConversation(bool reset, string text)
    {
        var conversation = new List<ChatMessage>();
        if (!reset)
        {
            for (int i = 0; i < Messages.Count - 1; i++)
            {
                ChatItem m = Messages[i];
                if (m.Role != "user" && m.Role != "assistant")
                {
                    continue;
                }
                if (m.Role == "assistant" && m.Content.StartsWith("[new session:"))
                {
                    continue;
                }
                if (m.Content.Length == 0)
                {
                    continue;
                }
                conversation.Add(new ChatMessage(m.Role, m.Content));
            }
        }
        conversation.Add(new ChatMessage("user", text));
        return conversation;
    }

    private void SetTurboStatus(string kind, string? detail)
    {
        TurboStatusText.Text = kind switch
        {
            "normal" => "Turbo: off",
            "on" => "Turbo: on",
            "starting" => "Turbo: starting...",
            "ready" => "Turbo: ready",
            "unavailable" => "Turbo: unavailable",
            "error" => "Turbo: error",
            _ => "Turbo: " + kind,
        };
        TurboStatusText.ToolTip = detail;
    }

    private void ClearLoop_Click(object sender, RoutedEventArgs e) => LoopItems.Clear();

    private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
        try
        {
            _turnCts?.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
        try
        {
            _client?.Dispose();
        }
        catch (ObjectDisposedException)
        {
        }
    }
}

public sealed class ChatItem : System.ComponentModel.INotifyPropertyChanged
{
    public string Role { get; init; } = "";

    private string _content = "";

    public string Content
    {
        get => _content;
        set
        {
            if (_content == value)
            {
                return;
            }
            _content = value;
            PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(nameof(Content)));
        }
    }

    public event System.ComponentModel.PropertyChangedEventHandler? PropertyChanged;
}

public sealed record LoopLineItem(string Time, string Kind, string Text, string? Detail)
{
    public string Display =>
        "[" + Time + "] " + Kind + ": " + Text + (string.IsNullOrEmpty(Detail) ? "" : " - " + Detail);
}
