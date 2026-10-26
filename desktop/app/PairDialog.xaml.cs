using System.Windows;
using System.Windows.Input;

namespace PocketForgeDesktop;

public partial class PairDialog : Window
{
    public string AcceptedCode { get; private set; } = "";

    public PairDialog()
    {
        InitializeComponent();
    }

    public void ShowError(string message) => ErrorText.Text = message;

    private void Pair_Click(object sender, RoutedEventArgs e)
    {
        ErrorText.Text = "";
        string code = CodeBox.Text.Trim();
        if (code.Length != 6 || !int.TryParse(code, out _))
        {
            ErrorText.Text = "Enter the 6-digit code (digits only).";
            return;
        }
        AcceptedCode = code;
        DialogResult = true;
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }

    private void CodeBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            Pair_Click(sender, e);
        }
    }
}
