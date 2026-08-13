using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Threading;

public class Program
{
    const int PW_RENDERFULLCONTENT = 0x00000002;
    const int PW_CLIENTONLY = 0x00000001;

    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd, out RECT r);
    [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
    [DllImport("user32.dll")] static extern void keybd_event(byte bVk, byte bScan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] static extern bool ScreenToClient(IntPtr hwnd, ref POINT lpPoint);
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr hwnd, out RECT lpRect);

    [StructLayout(LayoutKind.Sequential)]
    struct POINT { public int x, y; }

    const uint WM_LBUTTONDOWN = 0x0201;
    const uint WM_LBUTTONUP = 0x0202;
    const uint WM_KEYDOWN = 0x0100;
    const uint WM_KEYUP = 0x0101;

    const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    const uint MOUSEEVENTF_LEFTUP = 0x0004;
    const uint KEYEVENTF_KEYUP = 0x0002;

    [StructLayout(LayoutKind.Sequential)]
    struct RECT { public int Left, Top, Right, Bottom; }

    static IntPtr ParseHwnd(string s) => new IntPtr(long.Parse(s));

    public static int Main(string[] args)
    {
        if (args.Length < 1) { PrintUsage(); return 1; }
        try
        {
            switch (args[0])
            {
                case "screenshot": return DoScreenshot(args);
                case "click": return DoClick(args);
                case "key": return DoKey(args);
                case "activate": return DoActivate(args);
                case "list": return DoList(args);
                default: PrintUsage(); return 1;
            }
        }
        catch (Exception ex) { Console.Error.WriteLine("error: " + ex.Message); return 99; }
    }

    static int DoScreenshot(string[] args)
    {
        if (args.Length < 3) { Console.Error.WriteLine("usage: screencap screenshot <hwnd> <out.png>"); return 1; }
        IntPtr hwnd = ParseHwnd(args[1]);
        string outPath = args[2];
        if (!IsWindow(hwnd)) { Console.Error.WriteLine($"hwnd {hwnd} is not a window"); return 2; }
        RECT r;
        if (!GetWindowRect(hwnd, out r)) { Console.Error.WriteLine("GetWindowRect failed"); return 3; }
        int w = r.Right - r.Left, h = r.Bottom - r.Top;
        if (w <= 0 || h <= 0) { Console.Error.WriteLine("Empty window"); return 4; }
        using (var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb))
        using (var g = Graphics.FromImage(bmp))
        {
            IntPtr hdc = g.GetHdc();
            bool ok = PrintWindow(hwnd, hdc, PW_RENDERFULLCONTENT);
            g.ReleaseHdc(hdc);
            if (!ok)
            {
                IntPtr hdc2 = g.GetHdc();
                ok = PrintWindow(hwnd, hdc2, PW_CLIENTONLY);
                g.ReleaseHdc(hdc2);
            }
            bmp.Save(outPath, ImageFormat.Png);
        }
        Console.WriteLine($"Saved {outPath} ({w}x{h})");
        return 0;
    }

    static int DoClick(string[] args)
    {
        if (args.Length < 4) { Console.Error.WriteLine("usage: screencap click <hwnd> <x> <y>"); return 1; }
        IntPtr hwnd = ParseHwnd(args[1]);
        int x = int.Parse(args[2]), y = int.Parse(args[3]);

        // Try PostMessage first (works across UIPI/sessions, sends directly to window proc)
        RECT wrect;
        GetWindowRect(hwnd, out wrect);
        POINT p = new POINT { x = x, y = y };
        ScreenToClient(hwnd, ref p);
        IntPtr lParam = (IntPtr)((p.y << 16) | (p.x & 0xFFFF));
        bool pmd = PostMessage(hwnd, WM_LBUTTONDOWN, (IntPtr)0x0001, lParam);
        Thread.Sleep(40);
        bool pmu = PostMessage(hwnd, WM_LBUTTONUP, (IntPtr)0x0000, lParam);
        Console.WriteLine($"PostMessage: down={pmd} up={pmu} lParam=0x{lParam:X8}");

        // Also try real mouse as fallback
        bool fg = SetForegroundWindow(hwnd);
        bool sp = SetCursorPos(x, y);
        if (fg && sp)
        {
            Thread.Sleep(60);
            mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(40);
            mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
            Console.WriteLine($"Real mouse click also sent");
        }
        else
        {
            Console.WriteLine($"SetForeground={fg} SetCursor={sp} (real click skipped)");
        }
        return 0;
    }

    static int DoKey(string[] args)
    {
        if (args.Length < 3) { Console.Error.WriteLine("usage: screencap key <hwnd> <vk-or-name>"); return 1; }
        IntPtr hwnd = ParseHwnd(args[1]);
        byte vk = ResolveVk(args[2]);
        if (!SetForegroundWindow(hwnd)) { Console.Error.WriteLine("SetForegroundWindow failed"); }
        Thread.Sleep(150);
        keybd_event(vk, 0, 0, UIntPtr.Zero);
        Thread.Sleep(40);
        keybd_event(vk, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
        Console.WriteLine($"Pressed key {args[2]} (vk=0x{vk:X2})");
        return 0;
    }

    static int DoActivate(string[] args)
    {
        if (args.Length < 2) { Console.Error.WriteLine("usage: screencap activate <hwnd>"); return 1; }
        IntPtr hwnd = ParseHwnd(args[1]);
        bool ok = SetForegroundWindow(hwnd);
        Console.WriteLine($"Activate hwnd {hwnd}: {ok}");
        return ok ? 0 : 2;
    }

    static int DoList(string[] args)
    {
        var p = System.Diagnostics.Process.GetProcesses();
        foreach (var proc in p)
        {
            if (string.IsNullOrEmpty(proc.MainWindowTitle)) continue;
            Console.WriteLine($"{proc.Id}\t{proc.MainWindowHandle}\t{proc.MainWindowTitle}");
        }
        return 0;
    }

    static byte ResolveVk(string s)
    {
        // Common names
        switch (s.ToLowerInvariant())
        {
            case "esc":
            case "escape": return 0x1B;
            case "enter":
            case "return": return 0x0D;
            case "tab": return 0x09;
            case "space": return 0x20;
            case "k": return 0x4B;
            case "comma": return 0xBC;
            case "b": return 0x42;
            case "shift+b": return 0x42; // caller wraps with shift
            case "shift+comma": return 0xBC;
        }
        if (s.StartsWith("0x")) return byte.Parse(s.Substring(2), System.Globalization.NumberStyles.HexNumber);
        if (byte.TryParse(s, out byte b)) return b;
        throw new ArgumentException($"unknown key: {s}");
    }

    static void PrintUsage()
    {
        Console.Error.WriteLine("usage: screencap <screenshot|click|key|activate|list> ...");
    }
}
