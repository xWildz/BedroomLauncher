# Bedroom TV control panel (v4, Material look). Start it by double-clicking "TV Control Panel.cmd" in the project folder.
#
# Look:  rounded cards and tiles drawn with anti-aliasing (small C# controls compiled at start-up),
#        Windows' own icon font (Segoe Fluent Icons / Segoe MDL2 Assets) for every button.
# Base:  quick remote buttons talk to the TV directly; longer jobs run in the background so the window
#        never freezes, and their output streams into the Activity log.
# Settings (TV address) live in tools\panel-settings.json.
#
# NOTE: PowerShell variable names ignore case ($card and $CARD are the same variable), so the theme,
# fonts and icons use names that nothing else reuses: $Theme, $Fonts, $Icon.

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName Microsoft.VisualBasic
[Windows.Forms.Application]::EnableVisualStyles()
# Show start-up problems in a popup (the panel runs without a console window).
trap {
    [Windows.Forms.MessageBox]::Show("The panel hit an error:`n`n$_`n`nLine $($_.InvocationInfo.ScriptLineNumber)", "Bedroom TV", "OK", "Error") | Out-Null
    break
}

# ---------- Custom controls (rounded, anti-aliased) ----------
if (-not ("BedroomUi.Tile" -as [type])) {
Add-Type -ReferencedAssemblies System.Windows.Forms, System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Windows.Forms;

namespace BedroomUi {
    public static class Shapes {
        public static GraphicsPath Round(RectangleF r, float rad) {
            GraphicsPath p = new GraphicsPath();
            float d = rad * 2;
            if (d > r.Height) d = r.Height;
            if (d > r.Width) d = r.Width;
            if (d <= 0) { p.AddRectangle(r); return p; }
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }
    }

    // A FlowLayoutPanel that draws itself as a rounded card (Material style: soft shadow, and an optional
    // gradient icon badge that floats over the card's top-left edge).
    public class Card : FlowLayoutPanel {
        public int Radius = 12;
        public Color Fill = Color.White;
        public Color Border = Color.Empty;
        public bool Shadow = true;
        public string HeaderGlyph = "";
        public Font HeaderFont;
        public Color H1 = Color.FromArgb(66, 66, 74);
        public Color H2 = Color.FromArgb(25, 25, 25);
        public int HeaderSize = 48;
        public Card() {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        }
        public static void PaintShadow(Graphics g, RectangleF body, float radius) {
            for (int i = 6; i >= 1; i--) {
                RectangleF s = body;
                s.Inflate(i * 0.55f, i * 0.55f);
                s.Offset(0, i * 0.75f);
                using (GraphicsPath p = Shapes.Round(s, radius + i))
                using (SolidBrush b = new SolidBrush(Color.FromArgb(7, 0, 0, 0))) g.FillPath(b, p);
            }
        }
        public static void PaintBadge(Graphics g, RectangleF h, Color c1, Color c2, string glyph, Font font) {
            RectangleF sh = h; sh.Inflate(-2, -2); sh.Offset(0, 4);
            using (GraphicsPath p = Shapes.Round(sh, 10))
            using (SolidBrush b = new SolidBrush(Color.FromArgb(70, c2))) g.FillPath(b, p);
            using (GraphicsPath p = Shapes.Round(h, 10))
            using (LinearGradientBrush b = new LinearGradientBrush(h, c1, c2, 45f)) g.FillPath(b, p);
            if (font != null && glyph.Length > 0)
                TextRenderer.DrawText(g, glyph, font, Rectangle.Round(h), Color.White,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
        }
        protected override void OnPaintBackground(PaintEventArgs e) {
            Color outer = Parent != null ? Parent.BackColor : Color.Black;
            Graphics g = e.Graphics;
            g.Clear(outer);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float top = HeaderGlyph.Length > 0 ? 16f : 0f;
            RectangleF body = Shadow ? new RectangleF(4f, top + 1f, Width - 9f, Height - top - 9f)
                                     : new RectangleF(0.5f, top + 0.5f, Width - 1.5f, Height - top - 1.5f);
            if (Shadow) PaintShadow(g, body, Radius);
            using (GraphicsPath p = Shapes.Round(body, Radius))
            using (SolidBrush b = new SolidBrush(Fill)) {
                g.FillPath(b, p);
                if (Border != Color.Empty) using (Pen pen = new Pen(Border, 1f)) g.DrawPath(pen, p);
            }
            if (HeaderGlyph.Length > 0) PaintBadge(g, new RectangleF(20f, 0f, HeaderSize, HeaderSize), H1, H2, HeaderGlyph, HeaderFont);
        }
    }

    // Material "stat card": badge, label + big value on the right, a divider, and a footer line.
    public class StatCard : Control {
        public string Glyph = "", Label = "", Value = "", Footer = "";
        public Font GlyphFont, LabelFont, ValueFont, FooterFont;
        public Color C1 = Color.FromArgb(66, 66, 74), C2 = Color.FromArgb(25, 25, 25);
        public Color Fore = Color.FromArgb(52, 71, 103), Sub = Color.FromArgb(123, 128, 154);
        public Color FooterColor = Color.FromArgb(123, 128, 154);
        public StatCard() {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        }
        protected override void OnPaint(PaintEventArgs e) {
            Graphics g = e.Graphics;
            g.Clear(Parent != null ? Parent.BackColor : BackColor);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
            RectangleF body = new RectangleF(4f, 17f, Width - 9f, Height - 26f);
            Card.PaintShadow(g, body, 12);
            using (GraphicsPath p = Shapes.Round(body, 12))
            using (SolidBrush b = new SolidBrush(Color.White)) g.FillPath(b, p);
            Card.PaintBadge(g, new RectangleF(18f, 0f, 58f, 58f), C1, C2, Glyph, GlyphFont);
            int right = Width - 22;
            TextRenderer.DrawText(g, Label, LabelFont ?? Font, new Rectangle(84, 24, right - 84, 20), Sub,
                TextFormatFlags.Right | TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine);
            TextRenderer.DrawText(g, Value, ValueFont ?? Font, new Rectangle(70, 42, right - 70, 34), Fore,
                TextFormatFlags.Right | TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine | TextFormatFlags.VerticalCenter);
            float y = Height - 44;
            using (LinearGradientBrush lb = new LinearGradientBrush(new RectangleF(10, y, Width - 20, 1),
                Color.FromArgb(0, 200, 200, 200), Color.FromArgb(0, 200, 200, 200), 0f)) {
                ColorBlend cb = new ColorBlend(3);
                cb.Colors = new Color[] { Color.FromArgb(0, 200, 200, 200), Color.FromArgb(160, 210, 214, 218), Color.FromArgb(0, 200, 200, 200) };
                cb.Positions = new float[] { 0f, 0.5f, 1f };
                lb.InterpolationColors = cb;
                g.FillRectangle(lb, 10, y, Width - 20, 1);
            }
            TextRenderer.DrawText(g, Footer, FooterFont ?? Font, new Rectangle(20, (int)y + 7, Width - 40, 22), FooterColor,
                TextFormatFlags.Left | TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine);
        }
    }

    // Rounded solid panel (the floating sidebar).
    public class RoundPanel : Panel {
        public int Radius = 14;
        public Color Fill = Color.FromArgb(38, 38, 45);
        public RoundPanel() {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        }
        protected override void OnPaintBackground(PaintEventArgs e) {
            e.Graphics.Clear(Parent != null ? Parent.BackColor : Color.White);
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Card.PaintShadow(e.Graphics, new RectangleF(3, 2, Width - 7, Height - 9), Radius);
            using (GraphicsPath p = Shapes.Round(new RectangleF(3, 2, Width - 7, Height - 9), Radius))
            using (SolidBrush b = new SolidBrush(Fill)) e.Graphics.FillPath(b, p);
        }
    }

    // A rounded button with an icon (glyph) and a caption. Vertical = icon above text.
    public class Tile : Control {
        public string Glyph = "";
        public string Caption = "";
        public Font GlyphFont;
        public bool Vertical = false;
        public bool Center = false;
        public bool NavStyle = false;
        public bool Selected = false;
        public bool ShowFocus = false;
        public int Radius = 12;
        public Color Fill = Color.FromArgb(240, 242, 245);
        public Color HoverFill = Color.FromArgb(227, 230, 236);
        public Color PressFill = Color.FromArgb(210, 214, 222);
        public Color Fore = Color.FromArgb(52, 71, 103);
        public Color GlyphColor = Color.FromArgb(233, 30, 99);
        public Color Accent = Color.FromArgb(233, 30, 99);
        public Color Fill2 = Color.Empty;   // set = gradient from the state colour to this one
        bool hover, down;

        public Tile() {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            Cursor = Cursors.Hand;
        }
        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { if (e.Button == MouseButtons.Left) { down = true; Invalidate(); } base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }
        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

        protected override void OnPaint(PaintEventArgs e) {
            Graphics g = e.Graphics;
            Color outer = Parent != null ? Parent.BackColor : BackColor;
            g.Clear(outer);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;

            Color f = down ? PressFill : (hover ? HoverFill : Fill);
            if (NavStyle) f = Selected ? HoverFill : (hover ? Fill : outer);
            bool grad = Fill2 != Color.Empty && (!NavStyle || Selected);
            RectangleF rr = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
            if (grad) {
                // soft coloured shadow under gradient buttons (Material)
                RectangleF sh = new RectangleF(3f, 4f, Width - 7f, Height - 5f);
                using (GraphicsPath sp = Shapes.Round(sh, Radius))
                using (SolidBrush sb = new SolidBrush(Color.FromArgb(down ? 30 : 55, Fill2))) g.FillPath(sb, sp);
                rr = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 3.5f);
            }
            using (GraphicsPath p = Shapes.Round(rr, Radius)) {
                if (grad) using (LinearGradientBrush b = new LinearGradientBrush(rr, f, Fill2, 45f)) g.FillPath(b, p);
                else using (SolidBrush b = new SolidBrush(f)) g.FillPath(b, p);
                if (ShowFocus && Focused) using (Pen pen = new Pen(Accent, 2f)) g.DrawPath(pen, p);
            }
            if (NavStyle && Selected && Fill2 == Color.Empty) using (SolidBrush b = new SolidBrush(Accent)) g.FillRectangle(b, 0, Height * 0.28f, 3, Height * 0.44f);

            Font gf = GlyphFont != null ? GlyphFont : Font;
            Color fore = Enabled ? Fore : Color.Gray;
            if (Vertical && Caption.Length == 0) {
                TextRenderer.DrawText(g, Glyph, gf, new Rectangle(0, 0, Width, Height), GlyphColor,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
            } else if (Vertical) {
                int half = Height / 2;
                TextRenderer.DrawText(g, Glyph, gf, new Rectangle(0, 4, Width, half + 4), GlyphColor,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.Bottom | TextFormatFlags.NoPadding);
                TextRenderer.DrawText(g, Caption, Font, new Rectangle(2, half + 10, Width - 4, Height - half - 12), fore,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.Top | TextFormatFlags.WordBreak | TextFormatFlags.EndEllipsis);
            } else if (Center && Glyph.Length == 0) {
                TextRenderer.DrawText(g, Caption, Font, new Rectangle(0, 0, Width, Height), fore,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine);
            } else {
                int x = NavStyle ? 16 : 14;
                if (Glyph.Length > 0) {
                    Size gs = TextRenderer.MeasureText(Glyph, gf, Size.Empty, TextFormatFlags.NoPadding);
                    TextRenderer.DrawText(g, Glyph, gf, new Rectangle(x, 0, gs.Width + 2, Height), GlyphColor,
                        TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.NoPadding);
                    x += gs.Width + 12;
                }
                TextRenderer.DrawText(g, Caption, Font, new Rectangle(x, 0, Width - x - 8, Height), fore,
                    TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine);
            }
        }
    }

    // A tile that grabs arrow keys while focused (keyboard control of the TV).
    public class KeyTile : Tile {
        public KeyTile() { SetStyle(ControlStyles.Selectable, true); TabStop = true; ShowFocus = true; }
        protected override bool IsInputKey(Keys keyData) { return true; }
        protected override void OnClick(EventArgs e) { Focus(); base.OnClick(e); }
    }
}
'@
}

# ---------- Settings ----------
$root = Split-Path $PSScriptRoot -Parent
$tools = $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$settingsFile = Join-Path $tools "panel-settings.json"
$Settings = @{ Tv = "" }
if (Test-Path $settingsFile) {
    try { (Get-Content $settingsFile -Raw | ConvertFrom-Json).psobject.Properties | ForEach-Object { $Settings[$_.Name] = $_.Value } } catch {}
}
# Your TV's address is kept out of git: panel-settings.json (Tools > TV address) or tools\tv-address.txt.
if (-not $Settings.Tv) {
    $tvFile = Join-Path $tools "tv-address.txt"
    if (Test-Path $tvFile) { $Settings.Tv = (Get-Content $tvFile -TotalCount 1).Trim() }
}
$Tv = $Settings.Tv
function Save-Settings { $Settings | ConvertTo-Json | Set-Content -Path $settingsFile -Encoding UTF8 }

# ---------- Theme ----------
function RGB($r, $g, $b) { [Drawing.Color]::FromArgb($r, $g, $b) }
$Theme = @{
    # Material Dashboard look: light page, white cards, dark floating sidebar, pink primary.
    Bg      = RGB 240 242 245
    Side    = RGB 32 32 38
    SideHi  = RGB 58 58 68
    SideText = RGB 255 255 255
    SideSub = RGB 176 178 190
    Card    = RGB 255 255 255
    Tile    = RGB 240 242 245
    TileHi  = RGB 227 230 236
    TilePr  = RGB 210 214 222
    Accent  = RGB 233 30 99
    AccentT = RGB 236 64 122
    AccentH = RGB 240 98 146
    Accent2 = RGB 216 27 96
    Danger  = RGB 239 83 80
    DangerH = RGB 244 115 112
    Danger2 = RGB 229 57 53
    OnAccent = RGB 255 255 255
    Text    = RGB 52 71 103
    Sub     = RGB 123 128 154
    Faint   = RGB 170 175 192
    Good    = RGB 76 175 80
    Bad     = RGB 244 67 53
    Gold    = RGB 251 140 0
    Log     = RGB 255 255 255
    LogText = RGB 52 71 103
}
# Badge gradients (dark, pink, green, blue, orange, red)
$Badges = @(
    @((RGB 66 66 74), (RGB 25 25 25)),
    @((RGB 236 64 122), (RGB 216 27 96)),
    @((RGB 102 187 106), (RGB 67 160 71)),
    @((RGB 73 163 241), (RGB 26 115 232)),
    @((RGB 255 167 38), (RGB 251 140 0)),
    @((RGB 239 83 80), (RGB 229 57 53))
)
$Script:BadgeIx = 0

$installed = (New-Object Drawing.Text.InstalledFontCollection).Families | ForEach-Object { $_.Name }
$iconFamily = if ($installed -contains "Segoe Fluent Icons") { "Segoe Fluent Icons" } elseif ($installed -contains "Segoe MDL2 Assets") { "Segoe MDL2 Assets" } else { $null }
$Fonts = @{
    H1    = New-Object Drawing.Font("Segoe UI Semibold", 17)
    Stat  = New-Object Drawing.Font("Segoe UI Semibold", 18)
    Crumb = New-Object Drawing.Font("Segoe UI", 8.75)
    H2    = New-Object Drawing.Font("Segoe UI Semibold", 11)
    Body  = New-Object Drawing.Font("Segoe UI", 9.75)
    Small = New-Object Drawing.Font("Segoe UI", 8.75)
    Nav   = New-Object Drawing.Font("Segoe UI Semibold", 10.25)
    Mono  = New-Object Drawing.Font("Consolas", 9)
    Brand = New-Object Drawing.Font("Segoe UI Semibold", 14)
    Ico   = if ($iconFamily) { New-Object Drawing.Font($iconFamily, 13) } else { New-Object Drawing.Font("Segoe UI Symbol", 12) }
    IcoL  = if ($iconFamily) { New-Object Drawing.Font($iconFamily, 18) } else { New-Object Drawing.Font("Segoe UI Symbol", 16) }
    IcoS  = if ($iconFamily) { New-Object Drawing.Font($iconFamily, 11) } else { New-Object Drawing.Font("Segoe UI Symbol", 10) }
}

# Icons (Segoe Fluent Icons / MDL2 code points). Text fallbacks if neither font is installed.
function Glyph([int]$code, [string]$fallback) { if ($iconFamily) { [string][char]$code } else { $fallback } }
$Icon = @{
    Remote = Glyph 0xE7F4 "TV";  Apps = Glyph 0xE71D "::";   Games = Glyph 0xE7FC "G";    Tools = Glyph 0xE90F "*";   Undo = Glyph 0xE7A7 "<-"
    Up = Glyph 0xE70E "^";       Down = Glyph 0xE70D "v";    Left = Glyph 0xE76B "<";     Right = Glyph 0xE76C ">";   Ok = Glyph 0xE73E "OK"
    Back = Glyph 0xE72B "<";     Home = Glyph 0xE80F "H";    Menu = Glyph 0xE700 "="
    Prev = Glyph 0xE892 "|<";    Play = Glyph 0xE768 ">";    Pause = Glyph 0xE769 "||";   Next = Glyph 0xE893 ">|"
    VolDown = Glyph 0xE993 "-";  Mute = Glyph 0xE74F "x";    VolUp = Glyph 0xE995 "+"
    Moon = Glyph 0xE708 "z";     Sun = Glyph 0xE706 "o";     Power = Glyph 0xE7E8 "P";    Keyboard = Glyph 0xE765 "K"; Send = Glyph 0xE724 ">"
    Video = Glyph 0xE714 "V";    Music = Glyph 0xE8D6 "M";   Settings = Glyph 0xE713 "S"; Bluetooth = Glyph 0xE702 "B"; Edit = Glyph 0xE70F "E"
    List = Glyph 0xE8FD "="; Open = Glyph 0xE8A7 ">"; Refresh = Glyph 0xE72C "R"; Close = Glyph 0xE711 "x"
    Upload = Glyph 0xE898 "^";   Download = Glyph 0xE896 "v"; Folder = Glyph 0xE838 "F";  Save = Glyph 0xE74E "S"
    Weather = Glyph 0xE706 "o";  Phone = Glyph 0xE8EA "P";   Clock = Glyph 0xE823 "T";    Bolt = Glyph 0xE945 "!";    Shield = Glyph 0xEA18 "#"
    Link = Glyph 0xE71B "~";     Camera = Glyph 0xE722 "C";  Health = Glyph 0xE9D9 "+";   Globe = Glyph 0xE774 "@";   Restart = Glyph 0xE777 "R"
    Warn = Glyph 0xE7BA "!";     Info = Glyph 0xE946 "i";    Dot = [string][char]0x25CF
}

# ---------- Window ----------
$form = New-Object Windows.Forms.Form
$form.Text = "Bedroom TV"
$form.Size = New-Object Drawing.Size(1180, 840)
$form.MinimumSize = New-Object Drawing.Size(900, 620)
$form.StartPosition = "CenterScreen"
$form.BackColor = $Theme.Bg
$form.ForeColor = $Theme.Text
$form.Font = $Fonts.Body

$content = New-Object Windows.Forms.Panel
$content.Dock = "Fill"
$content.BackColor = $Theme.Bg

$sideOuter = New-Object Windows.Forms.Panel
$sideOuter.Dock = "Left"
$sideOuter.Width = 236
$sideOuter.BackColor = $Theme.Bg
$sideOuter.Padding = New-Object Windows.Forms.Padding(14, 14, 8, 14)
$side = New-Object BedroomUi.RoundPanel
$side.Dock = "Fill"
$side.Fill = $Theme.Side
$side.BackColor = $Theme.Side
$side.Padding = New-Object Windows.Forms.Padding(6, 6, 8, 12)
$sideOuter.Controls.Add($side)

$navList = New-Object Windows.Forms.FlowLayoutPanel
$navList.Dock = "Fill"
$navList.FlowDirection = "TopDown"
$navList.WrapContents = $false
$navList.BackColor = $Theme.Side
$navList.Padding = New-Object Windows.Forms.Padding(12, 8, 12, 12)
$side.Controls.Add($navList)

$brand = New-Object Windows.Forms.Panel
$brand.Dock = "Top"
$brand.Height = 80
$brand.BackColor = $Theme.Side
$side.Controls.Add($brand)

$logPanel = New-Object Windows.Forms.Panel
$logPanel.Dock = "Bottom"
$logPanel.Height = 170
$logPanel.BackColor = $Theme.Log

$top = New-Object Windows.Forms.Panel
$top.Dock = "Top"
$top.Height = 58
$top.BackColor = $Theme.Bg

# Dock order: added later = docked first.
$form.Controls.Add($content)
$form.Controls.Add($top)
$form.Controls.Add($logPanel)
$form.Controls.Add($sideOuter)

# Brand (sidebar top)
$brandIcon = New-Object Windows.Forms.Label
$brandIcon.Text = $Icon.Remote
$brandIcon.Font = $Fonts.IcoL
$brandIcon.ForeColor = $Theme.SideText
$brandIcon.AutoSize = $true
$brandIcon.Location = New-Object Drawing.Point(18, 24)
$brand.Controls.Add($brandIcon)
$brandText = New-Object Windows.Forms.Label
$brandText.Text = "Bedroom TV"
$brandText.Font = $Fonts.Brand
$brandText.ForeColor = $Theme.SideText
$brandText.AutoSize = $true
$brandText.Location = New-Object Drawing.Point(52, 18)
$brand.Controls.Add($brandText)
$brandSub = New-Object Windows.Forms.Label
$brandSub.Text = "Control panel"
$brandSub.Font = $Fonts.Small
$brandSub.ForeColor = $Theme.SideSub
$brandSub.AutoSize = $true
$brandSub.Location = New-Object Drawing.Point(54, 44)
$brand.Controls.Add($brandSub)
$brandLine = New-Object Windows.Forms.Label
$brandLine.BackColor = $Theme.SideHi
$brandLine.Size = New-Object Drawing.Size(176, 1)
$brandLine.Location = New-Object Drawing.Point(14, 72)
$brand.Controls.Add($brandLine)

# Status pill (top bar)
$pill = New-Object BedroomUi.Card
$pill.Shadow = $false
$pill.Fill = $Theme.Card
$pill.BackColor = $Theme.Card
$pill.Radius = 14
$pill.Location = New-Object Drawing.Point(20, 12)
$pill.AutoSize = $true
$pill.AutoSizeMode = "GrowAndShrink"
$pill.MinimumSize = New-Object Drawing.Size(220, 34)
$pill.Padding = New-Object Windows.Forms.Padding(12, 8, 14, 6)
$pill.WrapContents = $false
$top.Controls.Add($pill)
$statusDot = New-Object Windows.Forms.Label
$statusDot.Text = $Icon.Dot
$statusDot.ForeColor = $Theme.Faint
$statusDot.BackColor = $Theme.Card
$statusDot.AutoSize = $true
$statusDot.Margin = New-Object Windows.Forms.Padding(0, 0, 4, 0)
$pill.Controls.Add($statusDot)
$statusText = New-Object Windows.Forms.Label
$statusText.Text = "Checking the TV..."
$statusText.Font = $Fonts.Small
$statusText.ForeColor = $Theme.Sub
$statusText.BackColor = $Theme.Card
$statusText.AutoSize = $true
$statusText.Margin = New-Object Windows.Forms.Padding(0, 1, 0, 0)
$pill.Controls.Add($statusText)

$busyText = New-Object Windows.Forms.Label
$busyText.Text = ""
$busyText.Font = $Fonts.Small
$busyText.ForeColor = $Theme.Accent
$busyText.AutoSize = $true
$busyText.Anchor = "Top, Right"
$busyText.Location = New-Object Drawing.Point(700, 22)
$top.Controls.Add($busyText)

# ---------- Corner mascot ----------
# Shows tools\corner.gif if you put one there (a transparent background blends best).
# Otherwise: Sparky, a spinning lightning coin drawn in the panel's colors.
$mascot = New-Object Windows.Forms.PictureBox
$mascot.Size = New-Object Drawing.Size(52, 52)
$mascot.BackColor = $Theme.Bg
$mascot.Anchor = "Top, Right"
$mascot.Location = New-Object Drawing.Point(($top.Width - 64), 3)
$mascot.Cursor = "Hand"
$top.Controls.Add($mascot)
$Script:SpinAngle = 0.0
$Script:SpinBoost = 0
$cornerGif = Join-Path $tools "corner.gif"
if (Test-Path $cornerGif) {
    $mascot.SizeMode = "Zoom"
    $mascot.Image = [Drawing.Image]::FromFile($cornerGif)
} else {
    $mascot.Add_Paint({
        param($s, $e)
        $g = $e.Graphics
        $g.SmoothingMode = "AntiAlias"
        $cx = 26.0; $cy = 26.0; $r = 19.0
        $k = [Math]::Cos($Script:SpinAngle)
        $w = [Math]::Max(1.5, [Math]::Abs($k) * $r)
        $glow = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(40, 143, 184, 255))
        $g.FillEllipse($glow, [float]($cx - $r - 4), [float]($cy - $r - 4), [float](2 * $r + 8), [float](2 * $r + 8))
        $gold = RGB 255 210 122
        $goldDark = RGB 196 146 60
        $face = if ($k -ge 0) { $gold } else { $goldDark }
        $b = New-Object Drawing.SolidBrush $face
        $g.FillEllipse($b, [float]($cx - $w), [float]($cy - $r), [float](2 * $w), [float](2 * $r))
        $pen = New-Object Drawing.Pen ($goldDark, 2)
        $g.DrawEllipse($pen, [float]($cx - $w), [float]($cy - $r), [float](2 * $w), [float](2 * $r))
        if ([Math]::Abs($k) -gt 0.2) {
            $pts = @(@(2, -13), @(-7, 2), @(-1, 2), @(-4, 13), @(7, -3), @(1, -3), @(4, -13)) | ForEach-Object {
                New-Object Drawing.PointF ([float]($cx + $_[0] * $k)), ([float]($cy + $_[1]))
            }
            $bolt = New-Object Drawing.SolidBrush ($Theme.Bg)
            $g.FillPolygon($bolt, [Drawing.PointF[]]$pts)
            $bolt.Dispose()
        }
        $glow.Dispose(); $b.Dispose(); $pen.Dispose()
    })
    $spin = New-Object Windows.Forms.Timer
    $spin.Interval = 40
    $spin.Add_Tick({
        $step = if ($Script:SpinBoost -gt 0) { $Script:SpinBoost--; 0.45 } else { 0.08 }
        $Script:SpinAngle = ($Script:SpinAngle + $step) % (2 * [Math]::PI)
        $mascot.Invalidate()
    })
    $spin.Start()
}
$mascot.Add_Click({ $Script:SpinBoost = 40 })

# ---------- Activity log (bottom, can be hidden) ----------
$logHead = New-Object Windows.Forms.Panel
$logHead.Dock = "Top"
$logHead.Height = 32
$logHead.BackColor = $Theme.Card
$logPanel.Controls.Add($logHead)
$logTitle = New-Object Windows.Forms.Label
$logTitle.Text = "Activity"
$logTitle.Font = $Fonts.H2
$logTitle.ForeColor = $Theme.Text
$logTitle.AutoSize = $true
$logTitle.Location = New-Object Drawing.Point(16, 6)
$logHead.Controls.Add($logTitle)

$logBox = New-Object Windows.Forms.TextBox
$logBox.Multiline = $true
$logBox.ScrollBars = "Vertical"
$logBox.ReadOnly = $true
$logBox.Dock = "Fill"
$logBox.BorderStyle = "None"
$logBox.BackColor = $Theme.Log
$logBox.ForeColor = $Theme.LogText
$logBox.Font = $Fonts.Mono
$logPanel.Controls.Add($logBox)
$logBox.BringToFront()

function Small-Tile($parent, [string]$glyph, [string]$text, [scriptblock]$action, [int]$x, [int]$w) {
    $t = New-Object BedroomUi.Tile
    $t.Glyph = $glyph; $t.Caption = $text; $t.GlyphFont = $Fonts.IcoS; $t.Font = $Fonts.Small
    $t.Fill = $Theme.Card; $t.HoverFill = $Theme.Tile; $t.PressFill = $Theme.TilePr
    $t.Fore = $Theme.Sub; $t.GlyphColor = $Theme.Sub; $t.Radius = 8
    $t.Size = New-Object Drawing.Size($w, 24)
    $t.Anchor = "Top, Right"
    $t.Location = New-Object Drawing.Point(($parent.Width - $x), 4)
    $t.Tag = $action
    $t.Add_Click({ param($s, $e) & $s.Tag })
    $parent.Controls.Add($t)
    return $t
}
$hideTile = Small-Tile $logHead $Icon.Down "Hide" {
    if ($logPanel.Height -gt 40) { $logPanel.Height = 32; $hideTile.Caption = "Show"; $hideTile.Glyph = $Icon.Up }
    else { $logPanel.Height = 170; $hideTile.Caption = "Hide"; $hideTile.Glyph = $Icon.Down }
    $hideTile.Invalidate()
} 96 84
Small-Tile $logHead $Icon.Close "Clear" { $logBox.Clear() } 186 84 | Out-Null

# ---------- Base helpers ----------
function Say([string]$text) {
    if (-not $text) { return }
    $logBox.AppendText("[$(Get-Date -Format HH:mm:ss)]  $text`r`n")
}
function Adb([string[]]$a) {
    $o = ((& $adb @a 2>&1 | ForEach-Object { "$_" }) -join "`r`n").Trim()
    if ($o) { Say $o }
}
function TvShell([string]$cmd) { Adb @("-s", $Tv, "shell", $cmd) }
# Remote buttons, the same way the phone app sends them: a signed UDP message to the TV's virtual remote
# (port 8766), sent twice in case Wi-Fi drops one. Near-instant, vs ~0.4 s for "adb shell input keyevent".
# The pairing key is read from the TV over adb when needed and only kept in memory (never saved).
$FastKeys = @{
    "KEYCODE_DPAD_UP" = "up"; "KEYCODE_DPAD_DOWN" = "down"; "KEYCODE_DPAD_LEFT" = "left"; "KEYCODE_DPAD_RIGHT" = "right"
    "KEYCODE_DPAD_CENTER" = "ok"; "KEYCODE_ENTER" = "enter"; "KEYCODE_BACK" = "back"; "KEYCODE_HOME" = "home"; "KEYCODE_MENU" = "menu"
    "KEYCODE_MEDIA_PLAY_PAUSE" = "playpause"; "KEYCODE_VOLUME_UP" = "volup"; "KEYCODE_VOLUME_DOWN" = "voldown"
    "KEYCODE_VOLUME_MUTE" = "mute"; "KEYCODE_SEARCH" = "search"; "KEYCODE_SETTINGS" = "settings"; "KEYCODE_DEL" = "backspace"
}
$Script:PairKey = $null
$Script:PairKeyTried = [DateTime]::MinValue
$Script:KeySeq = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000
$Script:Udp = $null

function Get-PairKey {
    if ($Script:PairKey) { return $Script:PairKey }
    if (([DateTime]::Now - $Script:PairKeyTried).TotalSeconds -lt 20) { return $null }  # don't retry on every press
    $Script:PairKeyTried = [DateTime]::Now
    $xml = ((& $adb -s $Tv shell "run-as com.bedroom.launcher cat shared_prefs/remote.xml" 2>&1) -join "")
    if ($xml -match 'name="key">([a-z2-7]{32})<') {
        $Script:PairKey = $Matches[1]
        Say "Fast remote ready (same instant method as the phone app)."
    }
    return $Script:PairKey
}

function Send-FastKey([string]$name) {
    $k = Get-PairKey
    if (-not $k) { return $false }
    try {
        if (-not $Script:Udp) { $Script:Udp = New-Object Net.Sockets.UdpClient }
        $Script:KeySeq = [Math]::Max($Script:KeySeq + 1, [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000)
        $body = "BRK1 $($Script:KeySeq) $name"
        $h = New-Object Security.Cryptography.HMACSHA256 (, [Text.Encoding]::UTF8.GetBytes($k))
        $mac = (($h.ComputeHash([Text.Encoding]::ASCII.GetBytes($body)) | ForEach-Object { $_.ToString("x2") }) -join "").Substring(0, 32)
        $h.Dispose()
        $bytes = [Text.Encoding]::ASCII.GetBytes("$body $mac")
        $hostName = $Tv.Split(":")[0]
        [void]$Script:Udp.Send($bytes, $bytes.Length, $hostName, 8766)
        [void]$Script:Udp.Send($bytes, $bytes.Length, $hostName, 8766)
        return $true
    } catch {
        $Script:Udp = $null
        return $false
    }
}

function Key([string]$k) {
    $n = $FastKeys[$k]
    if ($n -and (Send-FastKey $n)) { return }
    & $adb -s $Tv shell "input keyevent $k" 2>&1 | Out-Null   # buttons the virtual remote doesn't have (prev/next...)
}
function OpenApp([string]$pkg) { TvShell "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 && echo Opened $pkg" }
function Confirm([string]$q) { [Windows.Forms.MessageBox]::Show($q, "Bedroom TV", "YesNo", "Question") -eq "Yes" }

# Background jobs: the window stays responsive while scripts run.
$Jobs = New-Object System.Collections.ArrayList
function Run-Bg([string]$label, [scriptblock]$block, [object[]]$argList, [scriptblock]$after) {
    $j = Start-Job -ScriptBlock $block -ArgumentList $argList
    [void]$Jobs.Add([pscustomobject]@{ Job = $j; Label = $label; After = $after; Out = (New-Object System.Collections.ArrayList); Quiet = $label.StartsWith("~") })
    if (-not $label.StartsWith("~")) { Say "$label ..." }
}
function RunScript([string]$name, [string[]]$extra, [scriptblock]$after) {
    $p = Join-Path $tools $name
    $all = @($extra) + @("-Tv", $Tv)
    Run-Bg "$name $($extra -join ' ')" {
        param($p, $a)
        & powershell -NoProfile -ExecutionPolicy Bypass -File $p @a 2>&1 | ForEach-Object { "$_" }
    } @($p, $all) $after
}

$jobTimer = New-Object Windows.Forms.Timer
$jobTimer.Interval = 300
$jobTimer.Add_Tick({
    foreach ($r in @($Jobs)) {
        $lines = Receive-Job $r.Job 2>&1 | ForEach-Object { "$_" }
        foreach ($l in $lines) {
            [void]$r.Out.Add($l)
            if (-not $r.Quiet -and $l -notmatch '^(@@|REPLACED\|)') { Say $l }
        }
        if ($r.Job.State -ne "Running") {
            Remove-Job $r.Job -Force
            $Jobs.Remove($r)
            if ($r.After) { try { & $r.After $r.Out } catch { Say "Error: $_" } }
        }
    }
    $visible = @($Jobs | Where-Object { -not $_.Quiet })
    $busyText.Text = if ($visible.Count) { "Working: " + (($visible | ForEach-Object { $_.Label }) -join ", ") } else { "" }
    $busyText.Left = $top.Width - $busyText.Width - 76
    if ($visible.Count -and $Script:SpinBoost -lt 5) { $Script:SpinBoost = 8 }
})

# ---------- TV status ----------
function Refresh-Status {
    Run-Bg "~status" {
        param($adbPath, $tvAddr)
        & $adbPath connect $tvAddr 2>&1 | Out-Null
        $state = ((& $adbPath -s $tvAddr get-state 2>&1) -join "").Trim()
        if ($state -ne "device") { "@@offline|$state"; return }
        $cmd = "dumpsys package com.bedroom.launcher | grep -m1 versionName; grep MemAvailable /proc/meminfo; df -h /data | tail -1; dumpsys activity activities | grep -m1 mResumedActivity; dumpsys window windows | grep -m1 mCurrentFocus"
        $o = (& $adbPath -s $tvAddr shell $cmd 2>&1 | ForEach-Object { "$_" })
        "@@online|" + ($o -join "|")
    } @($adb, $Tv) {
        param($out)
        $line = $out | Where-Object { $_ -like "@@*" } | Select-Object -First 1
        if (-not $line -or $line -like "@@offline*") {
            $statusDot.ForeColor = $Theme.Bad
            $statusText.Text = "Can't reach the TV at $Tv  -  is it on?  (Tools > TV address to change)"
            $statTv.Value = "Offline"; $statTv.Footer = "Is the TV on?  ($Tv)"; $statTv.FooterColor = $Theme.Bad
            foreach ($c in @($statRam, $statDisk, $statApp)) { $c.Value = "-"; $c.Footer = "Waiting for the TV"; $c.FooterColor = $Theme.Sub; $c.Invalidate() }
            $statTv.Invalidate()
            return
        }
        $parts = $line.Substring(2).Split("|")
        $ver = ($parts | Where-Object { $_ -match "versionName=" } | ForEach-Object { ($_ -split "=")[1].Trim() }) | Select-Object -First 1
        $mem = ($parts | Where-Object { $_ -match "MemAvailable" } | ForEach-Object { [int](($_ -replace '\D', '')) / 1024 }) | Select-Object -First 1
        $free = ($parts | Where-Object { $_ -match "%" } | ForEach-Object { ($_ -split '\s+')[3] }) | Select-Object -First 1
        $app = ($parts | Where-Object { $_ -match "mResumedActivity|mCurrentFocus" } | ForEach-Object { if ($_ -match ' ([a-zA-Z0-9_.]+)/') { $Matches[1] } }) | Select-Object -First 1
        $statusDot.ForeColor = $Theme.Good
        $statusText.Text = ("Connected  {0}     Home {1}     RAM free {2:N0} MB     Storage free {3}     On screen: {4}" -f $Tv, $ver, $mem, $free, $app)
        $statTv.Value = "Online"; $statTv.Footer = "Home $ver  -  $Tv"; $statTv.FooterColor = $Theme.Good
        $statRam.Value = ("{0:N0} MB" -f $mem); $statRam.Footer = if ($mem -lt 200) { "Low - close some apps" } else { "Plenty for now" }
        $statRam.FooterColor = if ($mem -lt 200) { $Theme.Gold } else { $Theme.Good }
        $statDisk.Value = "$free"; $statDisk.Footer = "On the TV's built-in storage"; $statDisk.FooterColor = $Theme.Sub
        $statApp.Value = (Friendly-App $app); $statApp.Footer = if ($app) { $app } else { "Nothing reported" }; $statApp.FooterColor = $Theme.Sub
        foreach ($c in @($statTv, $statRam, $statDisk, $statApp)) { $c.Invalidate() }
    }
}
$statusTimer = New-Object Windows.Forms.Timer
$statusTimer.Interval = 30000
$statusTimer.Add_Tick({ if (-not ($Jobs | Where-Object { $_.Label -eq "~status" })) { Refresh-Status } })

# ---------- Page building blocks ----------
$Pages = [ordered]@{}
$NavTiles = @{}

function New-Page([string]$name, [string]$glyph, [string]$heading, [string]$blurb) {
    $p = New-Object Windows.Forms.FlowLayoutPanel
    $p.Dock = "Fill"
    $p.AutoScroll = $true
    $p.Padding = New-Object Windows.Forms.Padding(20, 6, 20, 16)
    $p.BackColor = $Theme.Bg
    $p.Visible = $false
    $c = New-Object Windows.Forms.Label
    $c.Text = "Pages  /  $name"
    $c.Font = $Fonts.Crumb
    $c.ForeColor = $Theme.Sub
    $c.AutoSize = $true
    $c.Margin = New-Object Windows.Forms.Padding(1, 4, 0, 0)
    $p.Controls.Add($c)
    $p.SetFlowBreak($c, $true)
    $h = New-Object Windows.Forms.Label
    $h.Text = $heading
    $h.UseMnemonic = $false
    $h.Font = $Fonts.H1
    $h.ForeColor = $Theme.Text
    $h.AutoSize = $true
    $h.Margin = New-Object Windows.Forms.Padding(0, 0, 0, 0)
    $p.Controls.Add($h)
    $p.SetFlowBreak($h, $true)
    $d = New-Object Windows.Forms.Label
    $d.Text = $blurb
    $d.UseMnemonic = $false
    $d.Font = $Fonts.Body
    $d.ForeColor = $Theme.Sub
    $d.AutoSize = $true
    $d.MaximumSize = New-Object Drawing.Size(820, 0)
    $d.Margin = New-Object Windows.Forms.Padding(1, 2, 0, 22)
    $p.Controls.Add($d)
    $p.SetFlowBreak($d, $true)
    $content.Controls.Add($p)
    $Pages[$name] = $p

    $n = New-Object BedroomUi.Tile
    $n.NavStyle = $true
    $n.Glyph = $glyph
    $n.Caption = $name
    $n.GlyphFont = $Fonts.Ico
    $n.Font = $Fonts.Nav
    $n.Size = New-Object Drawing.Size(182, 44)
    $n.Margin = New-Object Windows.Forms.Padding(0, 0, 0, 4)
    $n.Fill = $Theme.SideHi; $n.HoverFill = $Theme.AccentT; $n.Fill2 = $Theme.Accent2
    $n.Fore = $Theme.SideSub; $n.GlyphColor = $Theme.SideSub; $n.Accent = $Theme.Accent
    $n.Radius = 8
    $n.Tag = $name
    $n.Add_Click({ param($s, $e) Show-Page $s.Tag })
    $navList.Controls.Add($n)
    $NavTiles[$name] = $n
    return $p
}

function Show-Page([string]$name) {
    foreach ($k in @($Pages.Keys)) {
        $on = ($k -eq $name)
        $Pages[$k].Visible = $on
        $NavTiles[$k].Selected = $on
        $NavTiles[$k].Fore = if ($on) { $Theme.SideText } else { $Theme.SideSub }
        $NavTiles[$k].GlyphColor = if ($on) { $Theme.SideText } else { $Theme.SideSub }
        $NavTiles[$k].Invalidate()
    }
}

function New-Card($page, [string]$glyph, [string]$title, [int]$width = 420, [string]$note = "") {
    $card = New-Object BedroomUi.Card
    $card.Fill = $Theme.Card
    $card.BackColor = $Theme.Card
    $card.Width = $width
    $card.AutoSize = $true
    $card.AutoSizeMode = "GrowAndShrink"
    $card.MaximumSize = New-Object Drawing.Size($width, 0)
    $card.MinimumSize = New-Object Drawing.Size($width, 0)
    $card.Padding = New-Object Windows.Forms.Padding(16, 26, 12, 18)
    $card.Margin = New-Object Windows.Forms.Padding(0, 0, 16, 22)
    $card.HeaderGlyph = $glyph
    $card.HeaderFont = $Fonts.Ico
    $pair = $Badges[$Script:BadgeIx % $Badges.Count]; $Script:BadgeIx++
    $card.H1 = $pair[0]; $card.H2 = $pair[1]
    $tl = New-Object Windows.Forms.Label
    $tl.Text = $title
    $tl.UseMnemonic = $false
    $tl.Font = $Fonts.H2
    $tl.ForeColor = $Theme.Text
    $tl.BackColor = $Theme.Card
    $tl.AutoSize = $true
    $tl.Margin = New-Object Windows.Forms.Padding(58, 0, 0, 16)
    $card.Controls.Add($tl)
    $card.SetFlowBreak($tl, $true)
    if ($note) { New-Note $card $note }
    $page.Controls.Add($card)
    return $card
}

function New-Note($card, [string]$text, [int]$width = 0) {
    $l = New-Object Windows.Forms.Label
    $l.Text = $text
    $l.UseMnemonic = $false
    $l.Font = $Fonts.Small
    $l.ForeColor = $Theme.Sub
    $l.BackColor = $Theme.Card
    $l.AutoSize = $true
    $w = if ($width) { $width } else { $card.Width - 34 }
    $l.MaximumSize = New-Object Drawing.Size($w, 0)
    $l.Margin = New-Object Windows.Forms.Padding(0, 0, 0, 10)
    $card.Controls.Add($l)
    $card.SetFlowBreak($l, $true)
}

# Material button looks: accent = pink gradient, danger = red gradient, quiet = plain text, normal = light grey.
function Set-TileStyle($t, [string]$style) {
    switch ($style) {
        "accent" { $t.Fill = $Theme.AccentT; $t.HoverFill = $Theme.AccentH; $t.PressFill = $Theme.Accent2; $t.Fill2 = $Theme.Accent2; $t.GlyphColor = $Theme.OnAccent; $t.Fore = $Theme.OnAccent }
        "danger" { $t.Fill = $Theme.Danger; $t.HoverFill = $Theme.DangerH; $t.PressFill = $Theme.Danger2; $t.Fill2 = $Theme.Danger2; $t.GlyphColor = $Theme.OnAccent; $t.Fore = $Theme.OnAccent }
        "quiet"  { $t.Fill = $Theme.Card; $t.HoverFill = $Theme.Tile; $t.PressFill = $Theme.TilePr; $t.GlyphColor = $Theme.Sub; $t.Fore = $Theme.Sub }
    }
}

# Styles: normal, accent, danger, quiet. Vertical = icon above the caption.
function New-Tile($card, [string]$glyph, [string]$text, [scriptblock]$action, [string]$style = "normal", [int]$w = 120, [int]$h = 44, [switch]$Vertical) {
    $t = New-Object BedroomUi.Tile
    $t.Glyph = $glyph
    $t.Caption = $text
    $t.Vertical = [bool]$Vertical
    $t.Center = (-not $glyph)
    $t.GlyphFont = if ($Vertical) { $Fonts.IcoL } else { $Fonts.Ico }
    $t.Font = $Fonts.Body
    $t.Size = New-Object Drawing.Size($w, $h)
    $t.Margin = New-Object Windows.Forms.Padding(0, 0, 8, 8)
    $t.Radius = 8
    switch ($style) {
        default  { $t.Fill = $Theme.Tile; $t.HoverFill = $Theme.TileHi; $t.PressFill = $Theme.TilePr; $t.GlyphColor = $Theme.Accent; $t.Fore = $Theme.Text }
    }
    Set-TileStyle $t $style
    $t.Tag = $action
    $t.Add_Click({
        param($s, $e)
        $form.Cursor = "WaitCursor"
        try { & $s.Tag } catch { Say "Error: $_" } finally { $form.Cursor = "Default" }
    })
    $card.Controls.Add($t)
    return $t
}

function Break-Row($card) { $card.SetFlowBreak($card.Controls[$card.Controls.Count - 1], $true) }

function Spacer($card, [int]$w, [int]$h = 1) {
    $s = New-Object Windows.Forms.Label
    $s.Width = $w
    $s.Height = $h
    $s.BackColor = $Theme.Card
    $s.Margin = New-Object Windows.Forms.Padding(0)
    $card.Controls.Add($s)
}

function Pick-Files([string]$title, [string]$filter, [string]$dir) {
    $dlg = New-Object Windows.Forms.OpenFileDialog
    $dlg.Title = $title
    $dlg.Multiselect = $true
    $dlg.Filter = $filter
    if ($dir -and (Test-Path $dir)) { $dlg.InitialDirectory = $dir }
    if ($dlg.ShowDialog() -eq "OK") { return $dlg.FileNames }
    return @()
}

function Pick-From([string]$title, [string[]]$items) {
    $d = New-Object Windows.Forms.Form
    $d.Text = $title
    $d.Size = New-Object Drawing.Size(520, 400)
    $d.StartPosition = "CenterParent"
    $d.BackColor = $Theme.Bg
    $d.ForeColor = $Theme.Text
    $d.Font = $Fonts.Body
    $lb = New-Object Windows.Forms.ListBox
    $lb.Dock = "Fill"
    $lb.BackColor = $Theme.Card
    $lb.ForeColor = $Theme.Text
    $lb.BorderStyle = "None"
    $lb.ItemHeight = 22
    $items | ForEach-Object { [void]$lb.Items.Add($_) }
    $ok = New-Object Windows.Forms.Button
    $ok.Text = "Choose"
    $ok.Dock = "Bottom"
    $ok.Height = 40
    $ok.FlatStyle = "Flat"
    $ok.FlatAppearance.BorderSize = 0
    $ok.BackColor = $Theme.AccentT
    $ok.ForeColor = $Theme.OnAccent
    $ok.DialogResult = "OK"
    $d.Controls.Add($lb)
    $d.Controls.Add($ok)
    $d.AcceptButton = $ok
    if ($d.ShowDialog($form) -eq "OK" -and $lb.SelectedItem) { return [string]$lb.SelectedItem }
    return $null
}

function Styled-TextBox([int]$w) {
    $tb = New-Object Windows.Forms.TextBox
    $tb.Width = $w
    $tb.BackColor = $Theme.Tile
    $tb.ForeColor = $Theme.Text
    $tb.BorderStyle = "FixedSingle"
    $tb.Font = New-Object Drawing.Font("Segoe UI", 11)
    $tb.Margin = New-Object Windows.Forms.Padding(0, 6, 8, 8)
    return $tb
}

# =====================================================================
# Remote
# =====================================================================
$pg = New-Page "Remote" $Icon.Remote "Remote" "Drive the TV from your PC. Keyboard control: click the keyboard bar, then use the arrow keys, Enter, Esc (Back), Space (play/pause), H (Home), + / - and M (mute)."

# Stat cards (Material dashboard row): live from the status check every 30 s.
function New-Stat($page, [string]$glyph, [string]$label, [int]$badge) {
    $c = New-Object BedroomUi.StatCard
    $c.Glyph = $glyph; $c.Label = $label; $c.Value = "..."; $c.Footer = "Checking..."
    $c.GlyphFont = $Fonts.IcoL; $c.LabelFont = $Fonts.Small; $c.ValueFont = $Fonts.Stat; $c.FooterFont = $Fonts.Small
    $c.C1 = $Badges[$badge][0]; $c.C2 = $Badges[$badge][1]
    $c.Fore = $Theme.Text; $c.Sub = $Theme.Sub; $c.FooterColor = $Theme.Sub
    $c.Size = New-Object Drawing.Size(196, 128)
    $c.Margin = New-Object Windows.Forms.Padding(0, 0, 12, 22)
    $page.Controls.Add($c)
    return $c
}
$statTv   = New-Stat $pg $Icon.Remote "TV" 0
$statRam  = New-Stat $pg $Icon.Bolt "Free memory" 1
$statDisk = New-Stat $pg $Icon.Save "Free storage" 2
$statApp  = New-Stat $pg $Icon.Video "On screen" 3
$pg.SetFlowBreak($statApp, $true)
function Friendly-App([string]$pkg) {
    if (-not $pkg) { return "-" }
    $known = @{ "com.bedroom.launcher" = "Home"; "com.google.android.youtube.tv" = "YouTube"; "com.google.android.youtube.tvmusic" = "YouTube Music"
                "com.hulu.livingroomplus" = "Hulu"; "com.netflix.ninja" = "Netflix"; "com.retroarch" = "RetroArch"; "com.android.tv.settings" = "Settings" }
    if ($known.ContainsKey($pkg)) { return $known[$pkg] }
    $last = $pkg.Split('.')[-1]
    return $last.Substring(0, 1).ToUpper() + $last.Substring(1)
}

$card = New-Card $pg $Icon.Up "Navigate" 400
Spacer $card 118; New-Tile $card $Icon.Up "" { Key "KEYCODE_DPAD_UP" } "normal" 110 62 -Vertical | Out-Null; Break-Row $card
New-Tile $card $Icon.Left "" { Key "KEYCODE_DPAD_LEFT" } "normal" 110 62 -Vertical | Out-Null
New-Tile $card $Icon.Ok "OK" { Key "KEYCODE_DPAD_CENTER" } "accent" 110 62 -Vertical | Out-Null
New-Tile $card $Icon.Right "" { Key "KEYCODE_DPAD_RIGHT" } "normal" 110 62 -Vertical | Out-Null; Break-Row $card
Spacer $card 118; New-Tile $card $Icon.Down "" { Key "KEYCODE_DPAD_DOWN" } "normal" 110 62 -Vertical | Out-Null; Break-Row $card
Spacer $card 1 6; Break-Row $card
New-Tile $card $Icon.Back "Back" { Key "KEYCODE_BACK" } "normal" 110 | Out-Null
New-Tile $card $Icon.Home "Home" { Key "KEYCODE_HOME" } "normal" 110 | Out-Null
New-Tile $card $Icon.Menu "Menu" { Key "KEYCODE_MENU" } "normal" 110 | Out-Null; Break-Row $card

$kb = New-Object BedroomUi.KeyTile
$kb.Glyph = $Icon.Keyboard
$kb.Caption = "Keyboard control  (click here)"
$kb.GlyphFont = $Fonts.Ico
$kb.Font = $Fonts.Body
$kb.Size = New-Object Drawing.Size(346, 44)
$kb.Margin = New-Object Windows.Forms.Padding(0, 0, 0, 4)
$kb.Fill = $Theme.Bg; $kb.HoverFill = $Theme.Tile; $kb.PressFill = $Theme.TilePr
$kb.Fore = $Theme.Sub; $kb.GlyphColor = $Theme.Sub; $kb.Accent = $Theme.Accent
$kb.Add_GotFocus({ $kb.Caption = "Keyboard control ON  -  arrows, Enter, Esc, Space, H, +/-, M"; $kb.Fore = $Theme.Accent; $kb.GlyphColor = $Theme.Accent; $kb.Invalidate() })
$kb.Add_LostFocus({ $kb.Caption = "Keyboard control  (click here)"; $kb.Fore = $Theme.Sub; $kb.GlyphColor = $Theme.Sub; $kb.Invalidate() })
$kb.Add_KeyDown({
    param($s, $e)
    $map = @{
        "Up" = "KEYCODE_DPAD_UP"; "Down" = "KEYCODE_DPAD_DOWN"; "Left" = "KEYCODE_DPAD_LEFT"; "Right" = "KEYCODE_DPAD_RIGHT"
        "Return" = "KEYCODE_DPAD_CENTER"; "Escape" = "KEYCODE_BACK"; "Back" = "KEYCODE_BACK"; "Space" = "KEYCODE_MEDIA_PLAY_PAUSE"
        "H" = "KEYCODE_HOME"; "Oemplus" = "KEYCODE_VOLUME_UP"; "Add" = "KEYCODE_VOLUME_UP"
        "OemMinus" = "KEYCODE_VOLUME_DOWN"; "Subtract" = "KEYCODE_VOLUME_DOWN"; "M" = "KEYCODE_VOLUME_MUTE"
    }
    $k = $map["$($e.KeyCode)"]
    if ($k) { Key $k; $e.Handled = $true; $e.SuppressKeyPress = $true }
})
$card.Controls.Add($kb)

$card = New-Card $pg $Icon.Play "Playback & sound" 400
New-Tile $card $Icon.Prev "Previous" { Key "KEYCODE_MEDIA_PREVIOUS" } "normal" 83 70 -Vertical | Out-Null
New-Tile $card $Icon.Play "Play" { Key "KEYCODE_MEDIA_PLAY" } "accent" 83 70 -Vertical | Out-Null
New-Tile $card $Icon.Pause "Pause" { Key "KEYCODE_MEDIA_PAUSE" } "normal" 83 70 -Vertical | Out-Null
New-Tile $card $Icon.Next "Next" { Key "KEYCODE_MEDIA_NEXT" } "normal" 83 70 -Vertical | Out-Null; Break-Row $card
New-Tile $card $Icon.VolDown "Volume -" { Key "KEYCODE_VOLUME_DOWN" } "normal" 112 70 -Vertical | Out-Null
New-Tile $card $Icon.Mute "Mute" { Key "KEYCODE_VOLUME_MUTE" } "normal" 112 70 -Vertical | Out-Null
New-Tile $card $Icon.VolUp "Volume +" { Key "KEYCODE_VOLUME_UP" } "normal" 112 70 -Vertical | Out-Null

$card = New-Card $pg $Icon.Moon "Screen & power" 400 "Screen off turns the backlight off and keeps the sound playing. Rest pauses, mutes and darkens the TV but keeps it online, so the phone or PC can wake it. Any remote button brings the picture back."
New-Tile $card $Icon.Moon "Screen off" { TvShell "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op off" } "accent" 172 | Out-Null
New-Tile $card $Icon.Sun "Screen on" { TvShell "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op on" } "normal" 172 | Out-Null; Break-Row $card
New-Tile $card $Icon.Power "TV rest" { TvShell "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op rest" } "normal" 172 | Out-Null
New-Tile $card $Icon.Power "TV wake" { TvShell "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op wake" } "normal" 172 | Out-Null; Break-Row $card

# TV brightness slider (0-100): 25-100 = backlight (on a curve), below 25 = backlight off-ish plus a dark layer.
function Bright-Text([int]$v) { if ($v -lt 25) { "TV brightness: $v  (extra dim)" } else { "TV brightness: $v" } }
$brLabel = New-Object Windows.Forms.Label
$brLabel.Text = "TV brightness: ..."
$brLabel.Font = $Fonts.Body
$brLabel.ForeColor = $Theme.Text
$brLabel.BackColor = $Theme.Card
$brLabel.AutoSize = $true
$brLabel.Margin = New-Object Windows.Forms.Padding(2, 8, 0, 0)
$card.Controls.Add($brLabel); Break-Row $card
$brBar = New-Object Windows.Forms.TrackBar
$brBar.Minimum = 0; $brBar.Maximum = 100; $brBar.TickFrequency = 10; $brBar.SmallChange = 2; $brBar.LargeChange = 10
$brBar.Width = 352
$brBar.BackColor = $Theme.Card
$brBar.Margin = New-Object Windows.Forms.Padding(0, 0, 0, 4)
$card.Controls.Add($brBar)
$Script:BrightReady = $false
$brTimer = New-Object Windows.Forms.Timer
$brTimer.Interval = 220
$brTimer.Add_Tick({
    $brTimer.Stop()
    $v = $brBar.Value
    Run-Bg "~bright" { param($a, $t, $lv) & $a -s $t shell "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op bright --ei level $lv" 2>&1 | Out-Null } @($adb, $Tv, $v)
})
$brBar.Add_ValueChanged({
    $brLabel.Text = Bright-Text $brBar.Value
    if ($Script:PopBright -and -not $Script:PopBright.IsDisposed) {
        if ($Script:PopBright.Value -ne $brBar.Value) { $Script:PopBright.Value = $brBar.Value }
        $Script:PopBrightLabel.Text = Bright-Text $brBar.Value
    }
    if ($Script:BrightReady) { $brTimer.Stop(); $brTimer.Start() }
})
function Read-Brightness {
    Run-Bg "~brightread" {
        param($a, $t)
        & $a -s $t shell "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op bright" 2>&1 | ForEach-Object { "$_" }
    } @($adb, $Tv) {
        param($out)
        $txt = ($out -join " ")
        if ($txt -match '"level":(\d+)') {
            $Script:BrightReady = $false
            $brBar.Value = [Math]::Min(100, [int]$Matches[1])
            $brLabel.Text = Bright-Text $brBar.Value
            $Script:BrightReady = $true
        } else {
            $brLabel.Text = "TV brightness: can't read it (is the TV on?)"
        }
    }
}

$card = New-Card $pg $Icon.Remote "Live view" 400 "Opens the TV picture in its own window with a pop-up remote beside it. With smooth video set up it's real video (like the phone app, Alt+F = full screen); otherwise snapshots, 1-2 a second. Hulu, Netflix and other paid video show as black: their copy protection."
$livePic = New-Object Windows.Forms.PictureBox
$livePic.Size = New-Object Drawing.Size(366, 206)
$livePic.SizeMode = "Zoom"
$livePic.BackColor = [Drawing.Color]::Black
$livePic.Margin = New-Object Windows.Forms.Padding(0, 0, 0, 10)
$card.Controls.Add($livePic)
$card.SetFlowBreak($livePic, $true)
$Script:LiveOn = $false
$Script:LiveProc = $null
$Script:LiveTask = $null
$Script:LiveBuf = $null
$Script:LiveBig = $null
$Script:LiveRemote = $null
$Script:LiveFull = $false

function Set-Live([bool]$on) {
    $Script:LiveOn = $on
    $liveBtn.Caption = if ($on) { "Stop live view" } else { "Start live view" }
    $liveBtn.Glyph = if ($on) { $Icon.Pause } else { $Icon.Play }
    $liveBtn.Invalidate()
    if ($on) { $liveTimer.Start() } else { $liveTimer.Stop() }
}

# Keyboard on the live window: arrows, Enter, Esc/Backspace (Back), Space, H, +/-, M; F11 = full screen.
function Live-Key($e) {
    if ("$($e.KeyCode)" -eq "F11") { Toggle-LiveFull; $e.Handled = $true; return }
    if ("$($e.KeyCode)" -eq "Escape" -and $Script:LiveFull) { Toggle-LiveFull; $e.Handled = $true; return }
    $map = @{
        "Up" = "KEYCODE_DPAD_UP"; "Down" = "KEYCODE_DPAD_DOWN"; "Left" = "KEYCODE_DPAD_LEFT"; "Right" = "KEYCODE_DPAD_RIGHT"
        "Return" = "KEYCODE_DPAD_CENTER"; "Escape" = "KEYCODE_BACK"; "Back" = "KEYCODE_BACK"; "Space" = "KEYCODE_MEDIA_PLAY_PAUSE"
        "H" = "KEYCODE_HOME"; "Oemplus" = "KEYCODE_VOLUME_UP"; "Add" = "KEYCODE_VOLUME_UP"
        "OemMinus" = "KEYCODE_VOLUME_DOWN"; "Subtract" = "KEYCODE_VOLUME_DOWN"; "M" = "KEYCODE_VOLUME_MUTE"
    }
    $k = $map["$($e.KeyCode)"]
    if ($k) { Key $k; $e.Handled = $true; $e.SuppressKeyPress = $true }
}

function Toggle-LiveFull {
    $bw = $Script:LiveBig
    if (-not $bw -or $bw.IsDisposed) { return }
    $Script:LiveFull = -not $Script:LiveFull
    if ($Script:LiveFull) {
        $bw.FormBorderStyle = "None"; $bw.WindowState = "Normal"; $bw.WindowState = "Maximized"
        $bw.Controls["bar"].Visible = $false
    } else {
        $bw.FormBorderStyle = "Sizable"; $bw.WindowState = "Normal"
        $bw.Controls["bar"].Visible = $true
    }
    if ($Script:LiveRemote -and -not $Script:LiveRemote.IsDisposed) { $Script:LiveRemote.BringToFront(); $Script:LiveRemote.Activate() }
}

# The big picture window (opens with live view).
function Open-LiveWindow {
    if ($Script:LiveBig -and -not $Script:LiveBig.IsDisposed) { $Script:LiveBig.Activate(); return }
    $bw = New-Object Windows.Forms.Form
    $bw.Text = "Bedroom TV  -  live"
    $bw.Size = New-Object Drawing.Size(1120, 680)
    $bw.MinimumSize = New-Object Drawing.Size(480, 300)
    $bw.BackColor = [Drawing.Color]::Black
    $bw.StartPosition = "Manual"
    $wa = [Windows.Forms.Screen]::FromControl($form).WorkingArea
    $bw.Location = New-Object Drawing.Point(([Math]::Max($wa.Left, $wa.Left + ($wa.Width - 1120 - 270) / 2)), ($wa.Top + 40))
    $bw.KeyPreview = $true
    $bw.Icon = $form.Icon
    $bp = New-Object Windows.Forms.PictureBox
    $bp.Dock = "Fill"
    $bp.SizeMode = "Zoom"
    $bp.BackColor = [Drawing.Color]::Black
    $bp.Add_DoubleClick({ Toggle-LiveFull })
    $bw.Controls.Add($bp)
    # Bottom bar: remote / full screen / stop
    $bar = New-Object Windows.Forms.FlowLayoutPanel
    $bar.Name = "bar"
    $bar.Dock = "Bottom"
    $bar.Height = 52
    $bar.BackColor = $Theme.Side
    $bar.Padding = New-Object Windows.Forms.Padding(12, 6, 12, 4)
    $bw.Controls.Add($bar)
    foreach ($b in @(
        @($Icon.Remote, "Remote", { Open-PopRemote }),
        @($Icon.Open, "Full screen (F11)", { Toggle-LiveFull }),
        @($Icon.Close, "Stop live view", { $Script:LiveBig.Close() })
    )) {
        $t = New-Tile $bar $b[0] $b[1] $b[2] "normal" 170 38
        $t.Fill = $Theme.SideHi; $t.HoverFill = RGB 76 76 88; $t.PressFill = RGB 96 96 110
        $t.Fore = $Theme.SideText; $t.GlyphColor = $Theme.SideText
    }
    $hint = New-Object Windows.Forms.Label
    $hint.Text = "Keys work here: arrows, Enter, Esc, Space, H, +/-, M.  Double-click = full screen."
    $hint.ForeColor = $Theme.SideSub
    $hint.Font = $Fonts.Small
    $hint.AutoSize = $true
    $hint.Margin = New-Object Windows.Forms.Padding(10, 12, 0, 0)
    $bar.Controls.Add($hint)
    $bp.BringToFront()
    $bw.Tag = $bp
    $bw.Add_KeyDown({ param($s, $e) Live-Key $e })
    $bw.Add_FormClosed({
        $Script:LiveFull = $false
        if ($Script:LiveRemote -and -not $Script:LiveRemote.IsDisposed) { $Script:LiveRemote.Close() }
        Set-Live $false
    })
    if ($livePic.Image) { $bp.Image = $livePic.Image }
    $Script:LiveBig = $bw
    $bw.Show()
}

# Pop-up remote: its own small window that stays on top, docked beside the live picture.
function Open-PopRemote {
    if ($Script:LiveRemote -and -not $Script:LiveRemote.IsDisposed) { $Script:LiveRemote.Activate(); return }
    $rw = New-Object Windows.Forms.Form
    $rw.Text = "Remote"
    $rw.FormBorderStyle = "FixedToolWindow"
    $rw.TopMost = $true
    $rw.ClientSize = New-Object Drawing.Size(252, 590)
    $rw.BackColor = $Theme.Side
    $rw.KeyPreview = $true
    $rw.StartPosition = "Manual"
    $wa = [Windows.Forms.Screen]::FromControl($form).WorkingArea
    $bw = $Script:LiveBig
    $x = if ($bw -and -not $bw.IsDisposed -and -not $Script:LiveFull) { $bw.Right + 6 } else { $wa.Right - 270 }
    $y = if ($bw -and -not $bw.IsDisposed -and -not $Script:LiveFull) { $bw.Top } else { $wa.Top + 60 }
    if ($Script:ScrcpyProc -and $Script:ScrcpyRect) { $x = $Script:ScrcpyRect.Right + 10; $y = $Script:ScrcpyRect.Top - 30 }
    if ($x + 260 -gt $wa.Right) { $x = $wa.Right - 270 }
    $rw.Location = New-Object Drawing.Point($x, $y)
    $fl = New-Object Windows.Forms.FlowLayoutPanel
    $fl.Dock = "Fill"
    $fl.BackColor = $Theme.Side
    $fl.Padding = New-Object Windows.Forms.Padding(10, 12, 6, 6)
    $rw.Controls.Add($fl)

    function RBtn([string]$glyph, [string]$text, [scriptblock]$act, [int]$w, [int]$h, [string]$style = "dark") {
        $t = New-Tile $fl $glyph $text $act "normal" $w $h -Vertical:($text -eq "" -or $h -ge 54)
        if ($style -eq "accent") { Set-TileStyle $t "accent" }
        else { $t.Fill = $Theme.SideHi; $t.HoverFill = RGB 76 76 88; $t.PressFill = RGB 96 96 110; $t.Fore = $Theme.SideText; $t.GlyphColor = $Theme.SideText }
        return $t
    }
    function RGap([int]$w) {
        $g = New-Object Windows.Forms.Label
        $g.Size = New-Object Drawing.Size($w, 1); $g.BackColor = $Theme.Side; $g.Margin = New-Object Windows.Forms.Padding(0)
        $fl.Controls.Add($g)
    }
    function RBreak { $fl.SetFlowBreak($fl.Controls[$fl.Controls.Count - 1], $true) }

    RGap 78; RBtn $Icon.Up "" { Key "KEYCODE_DPAD_UP" } 70 56 | Out-Null; RBreak
    RBtn $Icon.Left "" { Key "KEYCODE_DPAD_LEFT" } 70 56 | Out-Null
    RBtn $Icon.Ok "OK" { Key "KEYCODE_DPAD_CENTER" } 70 56 "accent" | Out-Null
    RBtn $Icon.Right "" { Key "KEYCODE_DPAD_RIGHT" } 70 56 | Out-Null; RBreak
    RGap 78; RBtn $Icon.Down "" { Key "KEYCODE_DPAD_DOWN" } 70 56 | Out-Null; RBreak
    RGap 228; RBreak
    RBtn $Icon.Back "Back" { Key "KEYCODE_BACK" } 70 54 | Out-Null
    RBtn $Icon.Home "Home" { Key "KEYCODE_HOME" } 70 54 | Out-Null
    RBtn $Icon.Menu "Menu" { Key "KEYCODE_MENU" } 70 54 | Out-Null; RBreak
    RBtn $Icon.Prev "Prev" { Key "KEYCODE_MEDIA_PREVIOUS" } 70 54 | Out-Null
    RBtn $Icon.Play "Play/Pause" { Key "KEYCODE_MEDIA_PLAY_PAUSE" } 70 54 "accent" | Out-Null
    RBtn $Icon.Next "Next" { Key "KEYCODE_MEDIA_NEXT" } 70 54 | Out-Null; RBreak
    RBtn $Icon.VolDown "Vol -" { Key "KEYCODE_VOLUME_DOWN" } 70 54 | Out-Null
    RBtn $Icon.Mute "Mute" { Key "KEYCODE_VOLUME_MUTE" } 70 54 | Out-Null
    RBtn $Icon.VolUp "Vol +" { Key "KEYCODE_VOLUME_UP" } 70 54 | Out-Null; RBreak
    RBtn $Icon.Power "Rest / wake TV" {
        $Script:PopResting = -not $Script:PopResting
        TvShell ("am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op " + $(if ($Script:PopResting) { "rest" } else { "wake" }))
    } 228 40 | Out-Null; RBreak
    # Brightness: the same slider as the main window's (moving either one moves both; the main one sends it).
    $pl = New-Object Windows.Forms.Label
    $pl.Text = Bright-Text $brBar.Value
    $pl.Font = $Fonts.Body
    $pl.ForeColor = $Theme.SideText
    $pl.BackColor = $Theme.Side
    $pl.AutoSize = $true
    $pl.Margin = New-Object Windows.Forms.Padding(2, 10, 0, 0)
    $fl.Controls.Add($pl); RBreak
    $pb = New-Object Windows.Forms.TrackBar
    $pb.Minimum = 0; $pb.Maximum = 100; $pb.TickFrequency = 10; $pb.SmallChange = 2; $pb.LargeChange = 10
    $pb.Width = 228
    $pb.BackColor = $Theme.Side
    $pb.Value = $brBar.Value
    $pb.Margin = New-Object Windows.Forms.Padding(0, 0, 0, 0)
    $fl.Controls.Add($pb)
    # (event handlers run after this function has returned, so use the sender, not $pb)
    $pb.Add_ValueChanged({ param($s, $e) if ($brBar.Value -ne $s.Value) { $brBar.Value = $s.Value } })
    $Script:PopBright = $pb; $Script:PopBrightLabel = $pl
    $rw.Add_KeyDown({ param($s, $e) Live-Key $e })
    $Script:LiveRemote = $rw
    if ($Script:LiveBig -and -not $Script:LiveBig.IsDisposed) { $rw.Show($Script:LiveBig) } else { $rw.Show() }
}
$Script:PopResting = $false
$Script:PopBright = $null; $Script:PopBrightLabel = $null

# ---- Smooth video (scrcpy): real H.264 video from the TV's encoder, like the phone app ----
$scrcpyDir = Join-Path $tools "scrcpy"
$scrcpyExe = Join-Path $scrcpyDir "scrcpy.exe"
$Script:ScrcpyProc = $null
$Script:Quality = if ($Settings.LiveQuality) { [int]$Settings.LiveQuality } else { 0 }
$Qualities = @(
    @{ Name = "Light 360p"; Size = 640; Fps = 24; Rate = "1M" },
    @{ Name = "Smooth 480p"; Size = 854; Fps = 30; Rate = "2M" },
    @{ Name = "Sharp 720p"; Size = 1280; Fps = 30; Rate = "4M" }
)

function Start-SmoothVideo {
    $q = $Qualities[$Script:Quality]
    $wa = [Windows.Forms.Screen]::FromControl($form).WorkingArea
    $w = [Math]::Min(1120, $wa.Width - 300); $h = [int]($w * 9 / 16)
    $x = $wa.Left + [int](($wa.Width - $w - 270) / 2); $y = $wa.Top + 60
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $scrcpyExe
    $psi.WorkingDirectory = $scrcpyDir
    $psi.Arguments = ("-s {0} --no-audio --video-codec=h264 --max-size {1} --max-fps {2} --video-bit-rate {3} " +
        "--window-title ""Bedroom TV - live"" --window-x {4} --window-y {5} --window-width {6} --window-height {7} --shortcut-mod=lalt") -f `
        $Tv, $q.Size, $q.Fps, $q.Rate, $x, $y, $w, $h
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.EnvironmentVariables["ADB"] = $adb   # use the same adb as everything else (no version fights)
    try {
        $Script:ScrcpyProc = [Diagnostics.Process]::Start($psi)
        Say "Smooth video started ($($q.Name)). Alt+F = full screen; mouse and keyboard in the video window control the TV."
        $Script:ScrcpyRect = New-Object Drawing.Rectangle($x, $y, $w, $h)
        return $true
    } catch {
        Say "Couldn't start smooth video: $_"
        $Script:ScrcpyProc = $null
        return $false
    }
}

function Stop-SmoothVideo {
    if ($Script:ScrcpyProc) {
        try { if (-not $Script:ScrcpyProc.HasExited) { $Script:ScrcpyProc.Kill() } } catch {}
        $Script:ScrcpyProc = $null
    }
}

# Watches the video window: when it closes, live view is over (and the pop-up remote goes too).
$scrcpyWatch = New-Object Windows.Forms.Timer
$scrcpyWatch.Interval = 700
$scrcpyWatch.Add_Tick({
    if ($Script:ScrcpyProc -and $Script:ScrcpyProc.HasExited) {
        $code = $Script:ScrcpyProc.ExitCode
        $Script:ScrcpyProc = $null
        $scrcpyWatch.Stop()
        if ($Script:LiveRemote -and -not $Script:LiveRemote.IsDisposed) { $Script:LiveRemote.Close() }
        $liveBtn.Caption = "Start live view"; $liveBtn.Glyph = $Icon.Play; $liveBtn.Invalidate()
        $Script:LiveOn = $false
        if ($code -ne 0 -and $code -ne 2) { Say "Smooth video stopped (code $code). Is the TV on? Snapshot mode still works: Tools > TV address." }
    }
})

# Downloads the official scrcpy release for Windows from GitHub, checks its SHA-256, unzips to tools\scrcpy.
function Install-Scrcpy {
    Run-Bg "Setting up smooth video (scrcpy)" {
        param($dest)
        $ErrorActionPreference = "Stop"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/Genymobile/scrcpy/releases/latest" -Headers @{ "User-Agent" = "BedroomTV" }
        $asset = $rel.assets | Where-Object { $_.name -match '^scrcpy-win64-v[\d.]+\.zip$' } | Select-Object -First 1
        if (-not $asset) { "No Windows download found in scrcpy $($rel.tag_name)."; return }
        $sums = $rel.assets | Where-Object { $_.name -eq "SHA256SUMS.txt" } | Select-Object -First 1
        $tmp = Join-Path $env:TEMP $asset.name
        "Downloading $($asset.name) ($([Math]::Round($asset.size / 1MB, 1)) MB) from github.com/Genymobile/scrcpy ..."
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $tmp -UseBasicParsing -Headers @{ "User-Agent" = "BedroomTV" }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $tmp).Hash.ToLower()
        if ($sums) {
            $txt = (Invoke-WebRequest -Uri $sums.browser_download_url -UseBasicParsing -Headers @{ "User-Agent" = "BedroomTV" }).Content
            if ($txt -is [byte[]]) { $txt = [Text.Encoding]::ASCII.GetString($txt) }
            $line = ($txt -split "`n") | Where-Object { $_ -match [regex]::Escape($asset.name) } | Select-Object -First 1
            if (-not $line -or ($line.Trim() -split '\s+')[0].ToLower() -ne $hash) {
                Remove-Item -LiteralPath $tmp -Force
                "Checksum didn't match - download deleted, nothing installed. Try again later."
                return
            }
            "Checksum OK ($($rel.tag_name))."
        } else {
            "Note: this release has no checksum file; downloaded over HTTPS from GitHub ($($rel.tag_name))."
        }
        $unz = Join-Path $env:TEMP "scrcpy-unzip"
        if (Test-Path $unz) { Remove-Item -LiteralPath $unz -Recurse -Force }
        Expand-Archive -LiteralPath $tmp -DestinationPath $unz -Force
        $inner = Get-ChildItem -LiteralPath $unz -Directory | Select-Object -First 1
        $src = if ($inner) { $inner.FullName } else { $unz }
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        Copy-Item -Path (Join-Path $src "*") -Destination $dest -Recurse -Force
        Remove-Item -LiteralPath $tmp -Force
        Remove-Item -LiteralPath $unz -Recurse -Force
        "@@ok Smooth video ready: scrcpy $($rel.tag_name) in tools\scrcpy."
    } @($scrcpyDir) {
        param($out)
        if (Test-Path $scrcpyExe) {
            $setupTile.Caption = "Smooth video: on"; $setupTile.Glyph = $Icon.Ok; $setupTile.Invalidate()
            Say "Smooth video is set up. Start live view to use it."
        }
    }
}

$liveBtn = New-Tile $card $Icon.Play "Start live view" {
    if ($Script:LiveOn) {
        if ($Script:ScrcpyProc) { Stop-SmoothVideo }
        elseif ($Script:LiveBig -and -not $Script:LiveBig.IsDisposed) { $Script:LiveBig.Close() } else { Set-Live $false }
        return
    }
    if ((Test-Path $scrcpyExe) -and (Start-SmoothVideo)) {
        $Script:LiveOn = $true
        $liveBtn.Caption = "Stop live view"; $liveBtn.Glyph = $Icon.Pause; $liveBtn.Invalidate()
        $scrcpyWatch.Start()
        Open-PopRemote
        return
    }
    # Snapshot mode (no scrcpy, or it couldn't start)
    Set-Live $true
    Open-LiveWindow
    Open-PopRemote
} "accent" 180
New-Tile $card $Icon.Remote "Pop-up remote" { Open-PopRemote } "normal" 170 | Out-Null; Break-Row $card
$qualTile = New-Tile $card $Icon.Video ("Quality: " + $Qualities[$Script:Quality].Name) {
    $Script:Quality = ($Script:Quality + 1) % $Qualities.Count
    $Settings.LiveQuality = $Script:Quality; Save-Settings
    $qualTile.Caption = "Quality: " + $Qualities[$Script:Quality].Name; $qualTile.Invalidate()
    if ($Script:ScrcpyProc) { Say "New quality applies next time you start live view." }
} "normal" 180
$setupTile = New-Tile $card $(if (Test-Path $scrcpyExe) { $Icon.Ok } else { $Icon.Download }) $(if (Test-Path $scrcpyExe) { "Smooth video: on" } else { "Set up smooth video" }) {
    if (Test-Path $scrcpyExe) { Say "Smooth video (scrcpy) is installed in tools\scrcpy. Delete that folder to go back to snapshots."; return }
    if (Confirm "Download scrcpy (free, open-source screen mirroring, about 6 MB) from GitHub into tools\scrcpy? It gives smooth live video instead of snapshots.") { Install-Scrcpy }
} "normal" 170

# Grabs a screenshot with "adb exec-out screencap" without freezing the window: the copy runs in the
# background and the timer picks up the finished picture.
$liveTimer = New-Object Windows.Forms.Timer
$liveTimer.Interval = 120
$liveTimer.Add_Tick({
    if (-not $Script:LiveOn) { return }
    if (-not $Script:LiveProc) {
        $psi = New-Object Diagnostics.ProcessStartInfo
        $psi.FileName = $adb
        $psi.Arguments = "-s $Tv exec-out screencap -p"
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.CreateNoWindow = $true
        try {
            $Script:LiveProc = [Diagnostics.Process]::Start($psi)
            $Script:LiveBuf = New-Object IO.MemoryStream
            $Script:LiveTask = $Script:LiveProc.StandardOutput.BaseStream.CopyToAsync($Script:LiveBuf)
        } catch { $Script:LiveProc = $null }
        return
    }
    if (-not $Script:LiveTask.IsCompleted) { return }
    try {
        $bytes = $Script:LiveBuf.ToArray()
        if ($bytes.Length -gt 1000) {
            $ms = New-Object IO.MemoryStream (, $bytes)
            $raw = [Drawing.Image]::FromStream($ms)
            $bmp = New-Object Drawing.Bitmap $raw
            $raw.Dispose(); $ms.Dispose()
            $old = $livePic.Image
            $livePic.Image = $bmp
            if ($Script:LiveBig -and -not $Script:LiveBig.IsDisposed) { $Script:LiveBig.Tag.Image = $bmp }
            if ($old) { $old.Dispose() }
        }
    } catch {}
    try { $Script:LiveProc.Dispose() } catch {}
    $Script:LiveProc = $null
})

$card = New-Card $pg $Icon.Keyboard "Type on the TV" 400 "Sends text to whichever box is selected on the TV (search, sign-in names...)."
$typeBox = Styled-TextBox 240
$card.Controls.Add($typeBox)
New-Tile $card $Icon.Send "Send" {
    $txt = $typeBox.Text
    if ($txt) {
        $esc = ($txt -replace "'", "") -replace ' ', '%s'
        TvShell "input text '$esc'"
        $typeBox.Text = ""
    }
} "accent" 104 | Out-Null

# =====================================================================
# Apps
# =====================================================================
$pg = New-Page "Apps" $Icon.Apps "Apps" "Open apps on the TV."
$card = New-Card $pg $Icon.Video "Watch & listen" 400
New-Tile $card $Icon.Video "Hulu" { OpenApp "com.hulu.livingroomplus" } "normal" 172 | Out-Null
New-Tile $card $Icon.Video "YouTube" { OpenApp "com.google.android.youtube.tv" } "normal" 172 | Out-Null
New-Tile $card $Icon.Music "YouTube Music" { OpenApp "com.google.android.youtube.tvmusic" } "normal" 172 | Out-Null
New-Tile $card $Icon.Video "Netflix" { OpenApp "com.netflix.ninja" } "normal" 172 | Out-Null

$card = New-Card $pg $Icon.Apps "Any app" 400 "Load the list of every app on the TV, pick one, and open it."
$appPicker = New-Object Windows.Forms.ComboBox
$appPicker.DropDownStyle = "DropDownList"
$appPicker.Width = 352
$appPicker.BackColor = $Theme.Tile
$appPicker.ForeColor = $Theme.Text
$appPicker.FlatStyle = "Flat"
$appPicker.Font = New-Object Drawing.Font("Segoe UI", 10.5)
$appPicker.Margin = New-Object Windows.Forms.Padding(0, 0, 0, 10)
$card.Controls.Add($appPicker)
$card.SetFlowBreak($appPicker, $true)
New-Tile $card $Icon.Refresh "Load list" {
    Run-Bg "Listing TV apps" {
        param($adbPath, $tvAddr)
        & $adbPath -s $tvAddr shell "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER" 2>&1 |
            ForEach-Object { "$_".Trim() } | Where-Object { $_ -match '^[\w.]+/' } | ForEach-Object { "@@" + ($_ -split '/')[0] } | Sort-Object -Unique
    } @($adb, $Tv) {
        param($out)
        $appPicker.Items.Clear()
        $out | Where-Object { $_ -like "@@*" } | ForEach-Object { [void]$appPicker.Items.Add($_.Substring(2)) }
        if ($appPicker.Items.Count) { $appPicker.SelectedIndex = 0; Say "$($appPicker.Items.Count) apps listed." }
    }
} "normal" 172 | Out-Null
New-Tile $card $Icon.Open "Open" { if ($appPicker.SelectedItem) { OpenApp ([string]$appPicker.SelectedItem) } } "accent" 172 | Out-Null

$card = New-Card $pg $Icon.Settings "TV screens" 400
New-Tile $card $Icon.Settings "TV Settings" { TvShell "am start -a android.settings.SETTINGS" } "normal" 172 | Out-Null
New-Tile $card $Icon.Bluetooth "Pair Bluetooth" { TvShell "am start -n com.android.tv.settings/.accessories.AddAccessoryActivity" } "normal" 172 | Out-Null
New-Tile $card $Icon.Edit "Button Mapper" { TvShell "am start -a android.intent.action.VIEW -d market://details?id=flar2.homebutton" } "normal" 172 | Out-Null
New-Tile $card $Icon.Home "Bedroom Home" { Key "KEYCODE_HOME" } "normal" 172 | Out-Null

# =====================================================================
# Games & saves browser (separate window)
# =====================================================================
$FbRoot = "/sdcard/Android/data/com.retroarch/files"
$FbOpen = @("roms", "saves", "states", "_replaced")

function Fb-Q([string]$x) { "'" + ($x -replace "'", "'\''") + "'" }
function Fb-Locked([string]$p) {
    $rel = $p.Substring([Math]::Min($p.Length, $FbRoot.Length)).Trim('/')
    if (-not $rel) { return $true }
    $parts = $rel.Split('/')
    if ($parts.Count -eq 1) { return $true }
    return -not ($FbOpen -contains $parts[0].ToLower())
}
function Fb-Sh([string]$cmd) { ((& $adb -s $Tv shell $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n") }

function Open-FileBrowser {
    $w = New-Object Windows.Forms.Form
    $w.Text = "Games & saves on the TV"
    $w.Size = New-Object Drawing.Size(900, 640)
    $w.MinimumSize = New-Object Drawing.Size(700, 420)
    $w.StartPosition = "CenterParent"
    $w.BackColor = $Theme.Bg
    $w.ForeColor = $Theme.Text
    $w.Font = $Fonts.Body

    $bar = New-Object Windows.Forms.FlowLayoutPanel
    $bar.Dock = "Top"
    $bar.Height = 56
    $bar.BackColor = $Theme.Bg
    $bar.Padding = New-Object Windows.Forms.Padding(12, 10, 12, 0)
    $bar.WrapContents = $false

    $pathLbl = New-Object Windows.Forms.Label
    $pathLbl.Dock = "Top"
    $pathLbl.Height = 30
    $pathLbl.Font = $Fonts.H2
    $pathLbl.ForeColor = $Theme.Sub
    $pathLbl.BackColor = $Theme.Bg
    $pathLbl.Padding = New-Object Windows.Forms.Padding(16, 6, 0, 0)

    $status = New-Object Windows.Forms.Label
    $status.Dock = "Bottom"
    $status.Height = 28
    $status.ForeColor = $Theme.Sub
    $status.BackColor = $Theme.Card
    $status.Padding = New-Object Windows.Forms.Padding(14, 6, 0, 0)
    $status.Font = $Fonts.Small

    $lv = New-Object Windows.Forms.ListView
    $lv.Dock = "Fill"
    $lv.View = "Details"
    $lv.FullRowSelect = $true
    $lv.MultiSelect = $true
    $lv.HideSelection = $false
    $lv.BorderStyle = "None"
    $lv.BackColor = $Theme.Card
    $lv.ForeColor = $Theme.Text
    $lv.Font = New-Object Drawing.Font("Segoe UI", 10)
    [void]$lv.Columns.Add("Name", 420)
    [void]$lv.Columns.Add("Size", 110, "Right")
    [void]$lv.Columns.Add("Changed", 170)
    [void]$lv.Columns.Add("", 90)

    $w.Controls.Add($lv)
    $w.Controls.Add($status)
    $w.Controls.Add($pathLbl)
    $w.Controls.Add($bar)

    $state = @{ Path = $FbRoot; Items = @(); Sort = 0; Desc = $false }

    $fill = {
        $lv.BeginUpdate()
        $lv.Items.Clear()
        $key = switch ($state.Sort) { 1 { "Size" } 2 { "Modified" } default { "Name" } }
        $sorted = @($state.Items | Sort-Object @{ Expression = { -not $_.Dir } }, @{ Expression = $key; Descending = $state.Desc })
        foreach ($it in $sorted) {
            $icon = if ($it.Dir) { $Icon.Folder } else { "" }
            $li = New-Object Windows.Forms.ListViewItem(($(if ($it.Dir) { "[folder]  " } else { "" }) + $it.Name))
            [void]$li.SubItems.Add($(if ($it.Dir) { "" } else { "{0:N0} KB" -f [math]::Ceiling($it.Size / 1KB) }))
            [void]$li.SubItems.Add($it.Modified.ToString("yyyy-MM-dd HH:mm"))
            [void]$li.SubItems.Add($(if ($it.Locked) { "locked" } else { "" }))
            $li.Tag = $it
            if ($it.Dir) { $li.ForeColor = $Theme.Gold }
            if ($it.Locked) { $li.ForeColor = $Theme.Faint }
            [void]$lv.Items.Add($li)
        }
        $lv.EndUpdate()
        $rel = $state.Path.Substring($FbRoot.Length).Trim('/')
        $pathLbl.Text = "  RetroArch" + $(if ($rel) { "  /  " + ($rel -replace '/', '  /  ') } else { "" })
        $inside = $state.Path -ne $FbRoot
        $status.Text = "$($state.Items.Count) items.  Double-click a folder to open it; select files (Ctrl/Shift for several), then use the buttons." +
            $(if (-not $inside) { "  Open ROMs, saves or states to change files." } else { "" })
    }

    $load = {
        param([string]$p)
        $w.Cursor = "WaitCursor"
        $out = Fb-Sh ("[ -d " + (Fb-Q $p) + " ] || echo @@missing; find " + (Fb-Q $p) + " -mindepth 1 -maxdepth 1 -exec stat -c '%F|%s|%Y|%n' {} + 2>/dev/null")
        $w.Cursor = "Default"
        if ($out -match "@@missing") { [Windows.Forms.MessageBox]::Show("That folder doesn't exist any more.", "Bedroom TV") | Out-Null; return }
        $items = @()
        foreach ($line in ($out -split "`n")) {
            $pp = $line.Trim().Split('|', 4)
            if ($pp.Count -lt 4) { continue }
            $items += [pscustomobject]@{
                Name = $pp[3].Substring($pp[3].LastIndexOf('/') + 1)
                Path = $pp[3]
                Dir = $pp[0].StartsWith("directory")
                Size = [long]$pp[1]
                Modified = [DateTimeOffset]::FromUnixTimeSeconds([long]$pp[2]).LocalDateTime
                Locked = (Fb-Locked $pp[3])
            }
        }
        $state.Path = $p
        $state.Items = $items
        & $fill
    }

    $selected = { @($lv.SelectedItems | ForEach-Object { $_.Tag }) }

    function Fb-Btn([string]$glyph, [string]$text, [scriptblock]$action, [string]$style = "normal", [int]$wd = 108) {
        $t = New-Object BedroomUi.Tile
        $t.Glyph = $glyph; $t.Caption = $text; $t.GlyphFont = $Fonts.IcoS; $t.Font = $Fonts.Small
        $t.Size = New-Object Drawing.Size($wd, 36)
        $t.Margin = New-Object Windows.Forms.Padding(0, 0, 6, 0)
        $t.Radius = 10
        if ($style -eq "danger" -or $style -eq "accent") { Set-TileStyle $t $style }
        else { $t.Fill = $Theme.Tile; $t.HoverFill = $Theme.TileHi; $t.PressFill = $Theme.TilePr; $t.GlyphColor = $Theme.Accent }
        $t.Tag = $action
        $t.Add_Click({ param($s, $e) try { & $s.Tag } catch { [Windows.Forms.MessageBox]::Show("$_", "Bedroom TV") | Out-Null } })
        $bar.Controls.Add($t)
    }

    Fb-Btn $Icon.Back "Up" {
        if ($state.Path -ne $FbRoot) { & $load ($state.Path.Substring(0, $state.Path.LastIndexOf('/'))) }
    } "normal" 70
    Fb-Btn $Icon.Refresh "Refresh" { & $load $state.Path } "normal" 92
    Fb-Btn $Icon.Upload "Send here" {
        if ($state.Path -eq $FbRoot) { [Windows.Forms.MessageBox]::Show("Open ROMs, saves or states first.", "Bedroom TV") | Out-Null; return }
        $files = Pick-Files "Send files to this TV folder" "All files|*.*" (Join-Path $env:USERPROFILE "Downloads")
        if (-not $files) { return }
        $dest = $state.Path
        Run-Bg "Sending $($files.Count) file(s) to the TV" {
            param($adbPath, $tvAddr, $d, $fl)
            foreach ($f in $fl) { & $adbPath -s $tvAddr push $f "$d/$([IO.Path]::GetFileName($f))" 2>&1 | Select-Object -Last 1 | ForEach-Object { "$_" } }
        } @($adb, $Tv, $dest, $files) { if ($load) { & $load $state.Path } }
    } "accent" 108
    Fb-Btn $Icon.Download "Copy to PC" {
        $sel = & $selected
        if (-not $sel) { return }
        $dlg = New-Object Windows.Forms.FolderBrowserDialog
        $dlg.Description = "Copy the selected items to which PC folder?"
        if ($dlg.ShowDialog() -ne "OK") { return }
        $to = $dlg.SelectedPath
        $paths = @($sel | ForEach-Object { $_.Path })
        Run-Bg "Copying $($paths.Count) item(s) to the PC" {
            param($adbPath, $tvAddr, $pl, $dst)
            foreach ($p in $pl) { & $adbPath -s $tvAddr pull $p $dst 2>&1 | Select-Object -Last 1 | ForEach-Object { "$_" } }
        } @($adb, $Tv, $paths, $to) { Start-Process $to }
    } "normal" 108
    Fb-Btn $Icon.Edit "Rename" {
        $sel = & $selected
        if ($sel.Count -ne 1) { [Windows.Forms.MessageBox]::Show("Select one item to rename.", "Bedroom TV") | Out-Null; return }
        $it = $sel[0]
        if ($it.Locked) { [Windows.Forms.MessageBox]::Show("That's locked: RetroArch needs it.", "Bedroom TV") | Out-Null; return }
        $n = [Microsoft.VisualBasic.Interaction]::InputBox("New name:", "Rename", $it.Name)
        if (-not $n -or $n -eq $it.Name -or $n.Contains('/')) { return }
        $dest = $it.Path.Substring(0, $it.Path.LastIndexOf('/')) + "/" + $n
        $r = Fb-Sh ("if [ -e " + (Fb-Q $dest) + " ]; then echo @@exists; else mv " + (Fb-Q $it.Path) + " " + (Fb-Q $dest) + " && echo @@ok; fi")
        if ($r -match "@@exists") { [Windows.Forms.MessageBox]::Show("Something with that name is already there.", "Bedroom TV") | Out-Null }
        & $load $state.Path
    } "normal" 96
    Fb-Btn $Icon.Open "Move to..." {
        $sel = @(& $selected | Where-Object { -not $_.Locked })
        if (-not $sel) { return }
        $rel = $state.Path.Substring($FbRoot.Length).Trim('/')
        $d = [Microsoft.VisualBasic.Interaction]::InputBox("Move $($sel.Count) item(s) to which folder? (for example ROMs/GBA, saves, states)", "Move", $(if ($rel) { $rel } else { "ROMs" }))
        if (-not $d) { return }
        $d = $d.Trim('/').Trim()
        if ($d.Contains("..")) { return }
        $target = "$FbRoot/$d"
        if (Fb-Locked "$target/x" ) { [Windows.Forms.MessageBox]::Show("Files can only go inside ROMs, saves, states or _replaced.", "Bedroom TV") | Out-Null; return }
        Fb-Sh ("mkdir -p " + (Fb-Q $target)) | Out-Null
        foreach ($it in $sel) { Fb-Sh ("mv -n " + (Fb-Q $it.Path) + " " + (Fb-Q $target) + "/") | Out-Null }
        & $load $state.Path
    } "normal" 108
    Fb-Btn $Icon.Folder "New folder" {
        if ($state.Path -eq $FbRoot) { [Windows.Forms.MessageBox]::Show("Open ROMs, saves or states first.", "Bedroom TV") | Out-Null; return }
        $n = [Microsoft.VisualBasic.Interaction]::InputBox("Folder name:", "New folder", "")
        if (-not $n -or $n.Contains('/') -or $n.Contains("..")) { return }
        Fb-Sh ("mkdir -p " + (Fb-Q "$($state.Path)/$n")) | Out-Null
        & $load $state.Path
    } "normal" 108
    Fb-Btn $Icon.Close "Delete" {
        $sel = @(& $selected)
        $lockedSel = @($sel | Where-Object { $_.Locked })
        $sel = @($sel | Where-Object { -not $_.Locked })
        if ($lockedSel) { [Windows.Forms.MessageBox]::Show("$($lockedSel.Count) locked item(s) skipped: RetroArch needs them.", "Bedroom TV") | Out-Null }
        if (-not $sel) { return }
        $dirs = @($sel | Where-Object { $_.Dir }).Count
        $names = ($sel | Select-Object -First 8 | ForEach-Object { "  " + $_.Name }) -join "`n"
        if ($sel.Count -gt 8) { $names += "`n  ...and $($sel.Count - 8) more" }
        $what = if ($dirs) { "These items (folders with everything in them)" } else { "These files" }
        if (-not (Confirm "$what will be permanently deleted from the TV. This can't be undone.`n`n$names`n`nDelete?")) { return }
        foreach ($it in $sel) { Fb-Sh ("rm -rf " + (Fb-Q $it.Path)) | Out-Null }
        Say "Deleted $($sel.Count) item(s) from the TV."
        & $load $state.Path
    } "danger" 92

    $lv.Add_ColumnClick({
        param($s, $e)
        $col = [Math]::Min($e.Column, 2)
        if ($state.Sort -eq $col) { $state.Desc = -not $state.Desc } else { $state.Sort = $col; $state.Desc = ($col -ne 0) }
        & $fill
    })
    $lv.Add_DoubleClick({
        $it = $lv.SelectedItems | Select-Object -First 1
        if ($it -and $it.Tag.Dir) { & $load $it.Tag.Path }
    })
    $lv.Add_KeyDown({
        param($s, $e)
        if ($e.KeyCode -eq "Back" -and $state.Path -ne $FbRoot) { & $load ($state.Path.Substring(0, $state.Path.LastIndexOf('/'))) }
        if ($e.KeyCode -eq "Return") {
            $it = $lv.SelectedItems | Select-Object -First 1
            if ($it -and $it.Tag.Dir) { & $load $it.Tag.Path }
        }
    })

    $w.Add_Shown({ & $load $FbRoot })
    [void]$w.ShowDialog($form)
}

# =====================================================================
# Games
# =====================================================================
$pg = New-Page "Games" $Icon.Games "Games" "RetroArch games (ROMs) and save files. Use games you own."
$card = New-Card $pg $Icon.Games "RetroArch" 400
New-Tile $card $Icon.Games "Open RetroArch" { OpenApp "com.retroarch" } "accent" 172 | Out-Null
New-Tile $card $Icon.Close "Close RetroArch" { if (Confirm "Close RetroArch? Anything not saved in the game is lost.") { TvShell "am force-stop com.retroarch && echo Closed RetroArch" } } "normal" 172 | Out-Null
New-Tile $card $Icon.Folder "Browse games & saves" { Open-FileBrowser } "accent" 352 | Out-Null

$card = New-Card $pg $Icon.Upload "Games (ROMs)" 400 "Files are sorted into system folders automatically (.gba goes to GBA, .sfc to SNES...)."
New-Tile $card $Icon.Upload "Send games..." {
    $files = Pick-Files "Pick games to send to the TV (RetroArch)" "Games|*.nes;*.sfc;*.smc;*.gb;*.gbc;*.gba;*.nds;*.n64;*.z64;*.v64;*.md;*.gen;*.smd;*.sms;*.gg;*.cue;*.chd;*.pbp;*.zip;*.ips;*.bps;*.ups|All files|*.*" (Join-Path $env:USERPROFILE "Downloads")
    if ($files) { RunScript "roms.ps1" (@("-Push") + $files) }
} "accent" 172 | Out-Null
New-Tile $card $Icon.List "Games on TV" { RunScript "roms.ps1" @("-List") } "normal" 172 | Out-Null

$savesDir = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "RetroArch Saves"
$card = New-Card $pg $Icon.Save "Save files" 400 "Backups go to Documents\RetroArch Saves. If sending replaces a save, the old one is kept (see Undo)."
New-Tile $card $Icon.Download "Back up saves" {
    $dest = Join-Path $savesDir (Get-Date -Format "yyyy-MM-dd_HHmm")
    RunScript "saves.ps1" @("-Backup", "-To", $dest) { if (Test-Path $dest) { Start-Process $dest } }
} "accent" 172 | Out-Null
New-Tile $card $Icon.Upload "Send saves..." {
    $files = Pick-Files "Pick RetroArch save files (or a saves backup .zip)" "Saves|*.srm;*.sav;*.state;*.state*;*.rtc;*.dsv;*.mcr;*.mcd;*.eep;*.fla;*.sra;*.mpk;*.zip|All files|*.*" $savesDir
    if ($files) {
        $a = @("-Push") + $files
        $running = ((& $adb -s $Tv shell "pidof com.retroarch" 2>&1 | ForEach-Object { "$_" }) -join "").Trim()
        if ($running) {
            if (-not (Confirm "RetroArch is open on the TV and would overwrite the saves when it closes.`n`nClose RetroArch now and send? (Anything not saved in the game is lost.)")) { return }
            $a = @("-StopRetroArch") + $a
        }
        RunScript "saves.ps1" $a
    }
} "normal" 172 | Out-Null
New-Tile $card $Icon.List "Saves on TV" { RunScript "saves.ps1" @("-List") } "normal" 172 | Out-Null
New-Tile $card $Icon.Folder "Open backups" { New-Item -ItemType Directory -Force -Path $savesDir | Out-Null; Start-Process $savesDir } "quiet" 172 | Out-Null

# =====================================================================
# Bedroom
# =====================================================================
$pg = New-Page "Bedroom" $Icon.Moon "Bedroom" "Bedtime, night mode, the wake-up alarm, the photo frame and weekly backups. Alarm times and the rest of the settings are in the phone app's Bedroom tab."
function TvOp([string]$op) { TvShell "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op $op" }

$card = New-Card $pg $Icon.Moon "Bedtime and night" 400 "Bedtime: night mode, bedtime volume, sleep music, then the TV rests when the sleep timer ends."
New-Tile $card $Icon.Moon "Start bedtime" { TvOp "bedtime" } "accent" 172 | Out-Null
New-Tile $card $Icon.Clock "Test alarm" { if (Confirm "Ring the wake-up alarm now? (TV wakes, YouTube Music starts quietly and fades up.)") { TvOp "alarmtest" } } "normal" 172 | Out-Null; Break-Row $card
New-Tile $card $Icon.Moon "Night mode on" { TvOp "nighton" } "normal" 172 | Out-Null
New-Tile $card $Icon.Sun "Night mode off" { TvOp "nightoff" } "normal" 172 | Out-Null

$photoDir = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "TV Photo Frame"
$card = New-Card $pg $Icon.Camera "Photo frame" 400 "Put photos in Documents\TV Photo Frame. Sync sends new ones to the TV (shrunk to save space) and brings back photos added from the phone."
New-Tile $card $Icon.Refresh "Sync photos" { RunScript "photos.ps1" @() } "accent" 172 | Out-Null
New-Tile $card $Icon.Folder "Open folder" { New-Item -ItemType Directory -Force -Path $photoDir | Out-Null; Start-Process $photoDir } "normal" 172 | Out-Null; Break-Row $card
New-Tile $card $Icon.Video "Show on TV" { TvOp "frame" } "normal" 172 | Out-Null

$card = New-Card $pg $Icon.Save "Weekly backup" 400 "Every Sunday: copies RetroArch saves to Documents\RetroArch Saves\Weekly (keeps the last 8) and syncs the photo frame."
New-Tile $card $Icon.Clock "Turn on" { RunScript "weekly.ps1" @("-Install") } "accent" 172 | Out-Null
New-Tile $card $Icon.Close "Turn off" { RunScript "weekly.ps1" @("-Uninstall") } "normal" 172 | Out-Null; Break-Row $card
New-Tile $card $Icon.Play "Run it now" { RunScript "weekly.ps1" @() } "normal" 172 | Out-Null
New-Tile $card $Icon.Folder "Open backups" {
    $w = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "RetroArch Saves\Weekly"
    New-Item -ItemType Directory -Force -Path $w | Out-Null; Start-Process $w
} "quiet" 172 | Out-Null

# =====================================================================
# Tools
# =====================================================================
$pg = New-Page "Tools" $Icon.Tools "Tools" "Setup, tune-ups and checks. Everything here can be taken back on the Undo page."
$card = New-Card $pg $Icon.Home "Home screen" 400
New-Tile $card $Icon.Weather "Weather city..." {
    $place = [Microsoft.VisualBasic.Interaction]::InputBox("City or ZIP for the weather:", "Bedroom TV", "")
    if ($place) { RunScript "set-weather.ps1" @("-Place", $place) }
} "normal" 172 | Out-Null
New-Tile $card $Icon.Phone "Phone remote link" { RunScript "remote-setup.ps1" @("-Link") } "normal" 172 | Out-Null
New-Tile $card $Icon.Clock "Sleep timer: TV off" {
    if (Confirm "Let the sleep timer turn the TV fully off when it ends? (The Undo page can take this back.)") { RunScript "remote-setup.ps1" @("-Enable") }
} "normal" 172 | Out-Null

$card = New-Card $pg $Icon.Bolt "Speed & ads" 400 "Tune-up 1 turns off apps you don't use. Ad-block switches the TV to ad-blocking DNS."
New-Tile $card $Icon.Bolt "Tune-up 1" { RunScript "optimize.ps1" @("-Apply") } "normal" 172 | Out-Null
New-Tile $card $Icon.Shield "Ad-block ON" { RunScript "tune2.ps1" @("-Apply") } "normal" 172 | Out-Null

$card = New-Card $pg $Icon.Health "Check & fix" 400
New-Tile $card $Icon.Refresh "Refresh status" { Refresh-Status } "normal" 172 | Out-Null
New-Tile $card $Icon.Link "Reconnect" { Adb @("disconnect", $Tv); Adb @("connect", $Tv); Refresh-Status } "normal" 172 | Out-Null
New-Tile $card $Icon.Camera "Screenshot" {
    Run-Bg "Screenshot" {
        param($p, $tvAddr)
        & powershell -NoProfile -ExecutionPolicy Bypass -File $p -Name panel -Device "-s $tvAddr" 2>&1 | ForEach-Object { "$_" }
    } @((Join-Path $tools "shot.ps1"), $Tv) { Start-Process (Join-Path $root "shots") }
} "normal" 172 | Out-Null
New-Tile $card $Icon.Health "Hulu check" {
    RunScript "hulu-diag.ps1" @() { $f2 = Join-Path $root "hulu-diag.txt"; if (Test-Path $f2) { Start-Process notepad $f2 } }
} "normal" 172 | Out-Null
New-Tile $card $Icon.Globe "TV address..." {
    $n = [Microsoft.VisualBasic.Interaction]::InputBox("TV address (ip:port):", "Bedroom TV", $Tv)
    if ($n) { $Script:Tv = $n.Trim(); $Settings.Tv = $Script:Tv; Save-Settings; $Script:PairKey = $null; $Script:PairKeyTried = [DateTime]::MinValue; Say "TV address set to $Tv"; Refresh-Status }
} "normal" 172 | Out-Null
New-Tile $card $Icon.Folder "Project folder" { Start-Process $root } "quiet" 172 | Out-Null
New-Tile $card $Icon.Restart "Restart TV" { if (Confirm "Restart the TV now?") { Adb @("-s", $Tv, "reboot") } } "danger" 172 | Out-Null

# =====================================================================
# Undo
# =====================================================================
$pg = New-Page "Undo" $Icon.Undo "Undo" "Take back anything this project changed. Each one asks first. Nothing here deletes your apps, games or backups."

function Undo-Row($card, [string]$title, [string]$desc, [scriptblock]$action) {
    $inner = $card.Width - 34
    $row = New-Object Windows.Forms.FlowLayoutPanel
    $row.WrapContents = $false
    $row.AutoSize = $true
    $row.BackColor = $Theme.Card
    $row.Margin = New-Object Windows.Forms.Padding(0, 0, 0, 14)

    $txt = New-Object Windows.Forms.FlowLayoutPanel
    $txt.FlowDirection = "TopDown"
    $txt.WrapContents = $false
    $txt.AutoSize = $true
    $txt.MinimumSize = New-Object Drawing.Size(($inner - 132), 0)
    $txt.MaximumSize = New-Object Drawing.Size(($inner - 132), 0)
    $txt.BackColor = $Theme.Card
    $txt.Margin = New-Object Windows.Forms.Padding(0)
    $t1 = New-Object Windows.Forms.Label
    $t1.Text = $title
    $t1.Font = $Fonts.H2
    $t1.ForeColor = $Theme.Text
    $t1.BackColor = $Theme.Card
    $t1.AutoSize = $true
    $t1.Margin = New-Object Windows.Forms.Padding(0, 2, 0, 2)
    $t2 = New-Object Windows.Forms.Label
    $t2.Text = $desc
    $t2.Font = $Fonts.Small
    $t2.ForeColor = $Theme.Sub
    $t2.BackColor = $Theme.Card
    $t2.AutoSize = $true
    $t2.MaximumSize = New-Object Drawing.Size(($inner - 150), 0)
    $t2.Margin = New-Object Windows.Forms.Padding(0)
    $txt.Controls.Add($t1)
    $txt.Controls.Add($t2)
    $row.Controls.Add($txt)

    $b = New-Object BedroomUi.Tile
    $b.Glyph = $Icon.Undo
    $b.Caption = "Undo"
    $b.GlyphFont = $Fonts.IcoS
    $b.Font = $Fonts.Body
    $b.Size = New-Object Drawing.Size(120, 38)
    Set-TileStyle $b "danger"
    $b.Margin = New-Object Windows.Forms.Padding(12, 2, 0, 0)
    $b.Tag = $action
    $b.Add_Click({
        param($s, $e)
        try { & $s.Tag } catch { Say "Error: $_" }
    })
    $row.Controls.Add($b)
    $card.Controls.Add($row)
    $card.SetFlowBreak($row, $true)
}

$card = New-Card $pg $Icon.Home "Home screen & system" 816
Undo-Row $card "Normal Android TV home screen" "Turns the original Google home screen back on and makes it the default. Bedroom Home stays installed, so you can switch back later." {
    if (Confirm "Put the original Android TV home screen back?") { RunScript "home-step.ps1" @("-Step", "undo") }
}
Undo-Row $card "Tune-up 1 (disabled apps, animations, sleep)" "Re-enables Vudu, Spotify, Pluto, Twitch, voice search and the other apps tune-up 1 turned off, and resets the animation and sleep settings." {
    if (Confirm "Undo tune-up 1?") { RunScript "optimize.ps1" @("-Undo") }
}
Undo-Row $card "Ad-block DNS + tune-up 2" "Turns off the AdGuard ad-blocking DNS and re-enables the extra apps tune-up 2 turned off." {
    if (Confirm "Turn off ad-blocking and undo tune-up 2?") { RunScript "tune2.ps1" @("-Undo") }
}
Undo-Row $card "Sleep timer turning the TV off" "Removes the permission that lets the sleep timer switch the TV off. The timer then just pauses and goes Home." {
    if (Confirm "Remove the sleep timer's turn-off permission?") { RunScript "remote-setup.ps1" @("-Disable") }
}
Undo-Row $card "Phone app's now-playing access" "Turns off the media access the phone's Apps tab uses to show what's playing and control it. You can turn it back on from the phone's Apps tab." {
    if (Confirm "Turn off now-playing access?") { TvShell "cmd notification disallow_listener com.bedroom.launcher/com.bedroom.launcher.MediaListener && echo Now-playing access turned off" }
}
Undo-Row $card "Live view's invisible refresh dot" "Takes back the 'display over other apps' permission the phone's live video uses to keep refreshing on a still screen (it's only on screen while you're watching; the phone switches it back on next time you watch)." {
    if (Confirm "Remove the live view's overlay permission?") { TvShell "appops set com.bedroom.launcher SYSTEM_ALERT_WINDOW default && echo Overlay permission removed" }
}
Undo-Row $card "Weekly backup task" "Removes the Sunday backup + photo sync from Windows Task Scheduler. Backups already made are kept." {
    if (Confirm "Turn off the weekly backup?") { RunScript "weekly.ps1" @("-Uninstall") }
}
Undo-Row $card "Night mode stuck" "Puts the TV's normal brightness back and removes the warm tint." {
    TvShell "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op nightoff"
}
Undo-Row $card "Screen off stuck" "Forces the picture (backlight) back on, in case it didn't come back by itself." {
    TvShell "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op on"
}

$card = New-Card $pg $Icon.Games "RetroArch" 816
Undo-Row $card "RetroArch settings" "Puts back RetroArch's settings from before the ROM folder setup. The current settings are kept as a copy." {
    if (Confirm "Restore RetroArch's old settings? RetroArch will be closed.") { RunScript "roms.ps1" @("-RestoreConfig") }
}
Undo-Row $card "A replaced save file" "Pick an old save that 'Send saves' replaced and put it back. The current save is kept too, so this can be undone as well." {
    Run-Bg "Looking for replaced saves" {
        param($p, $tvAddr)
        & powershell -NoProfile -ExecutionPolicy Bypass -File $p -ListReplaced -Tv $tvAddr 2>&1 | ForEach-Object { "$_" }
    } @((Join-Path $tools "saves.ps1"), $Tv) {
        param($out)
        $items = @($out | Where-Object { $_ -like "REPLACED|*" } | ForEach-Object { $_.Substring(9) })
        if (-not $items) { Say "No replaced saves - nothing to undo."; return }
        $pick = Pick-From "Restore which save? (newest first)" $items
        if (-not $pick) { return }
        $a = @("-Restore", $pick)
        $running = ((& $adb -s $Tv shell "pidof com.retroarch" 2>&1 | ForEach-Object { "$_" }) -join "").Trim()
        if ($running) {
            if (-not (Confirm "RetroArch is open and would overwrite the save when it closes. Close it now? (Unsaved progress is lost.)")) { return }
            $a += "-StopRetroArch"
        }
        RunScript "saves.ps1" $a
    }
}

$card = New-Card $pg $Icon.Info "Can't be undone from here" 816
New-Note $card ("Button Mapper remaps (Netflix -> YouTube, VUDU -> Hulu): change them in the Button Mapper app on the TV.`r`n`r`n" +
    "Phone remote / gamepad access: TV Settings > Device Preferences > Developer options > Revoke USB debugging authorizations. " +
    "That also disconnects this PC until you accept the prompt on the TV again.`r`n`r`n" +
    "Games and save backups on the PC are never deleted by this panel.")

# ---------- Go ----------
Show-Page "Remote"
$form.Add_Shown({
    Say "Bedroom TV panel ready. TV: $Tv"
    if (-not $iconFamily) { Say "Windows icon font not found - using simple text icons." }
    $jobTimer.Start()
    $statusTimer.Start()
    Refresh-Status
})
$form.Add_Shown({ Read-Brightness })
$form.Add_FormClosing({ $jobTimer.Stop(); $statusTimer.Stop(); $liveTimer.Stop(); Stop-SmoothVideo; Get-Job | Remove-Job -Force -ErrorAction SilentlyContinue })
[void]$form.ShowDialog()
