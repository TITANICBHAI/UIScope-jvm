using System;
using UIScope.Helper;

static class JsonTests
{
    static int failed;
    static void Eq(string name, object expected, object actual)
    {
        bool ok = Equals(expected, actual);
        if (!ok) { failed++; Console.WriteLine("FAIL " + name + ": expected <" + expected + "> but was <" + actual + ">"); }
        else Console.WriteLine("ok   " + name);
    }

    static int Main()
    {
        Eq("plain", "\"abc\"", Json.Str("abc"));
        Eq("quote and backslash", "\"a\\\"b\\\\c\"", Json.Str("a\"b\\c"));
        Eq("control chars are escaped, never raw", "\"\\u0001\\b\\n\\t\\u001b\"", Json.Str("\u0001\b\n\t\u001b"));
        Eq("null is empty", "\"\"", Json.Str(null));
        Eq("non-ascii is kept", "\"caf\u00e9\"", Json.Str("caf\u00e9"));
        Eq("line separator is escaped", "\"\\u2028\"", Json.Str("\u2028"));
        Eq("rect", "{\"x\":-5,\"y\":7,\"width\":100,\"height\":50}", Json.Rect(-5, 7, 100, 50));
        Eq("hwnd", "0x000a0b0c", Json.Hwnd(new IntPtr(0xa0b0c)));

        string req = "{\"id\": 12, \"type\": \"captureWindow\", \"hwnd\": \"0x000a0b0c\", \"maxNodes\": 10000, \"hideFromCapture\": false, \"tmpDir\": \"C:\\\\Users\\\\Jos\\u00e9\\\\AppData\\\\Local\\\\UIScope\\\\tmp\"}";
        Eq("id", "12", Json.ExtractNumber(req, "id"));
        Eq("type", "captureWindow", Json.ExtractString(req, "type"));
        Eq("hwnd", "0x000a0b0c", Json.ExtractString(req, "hwnd"));
        Eq("int", 10000, Json.ExtractInt(req, "maxNodes", 5));
        Eq("int fallback", 40, Json.ExtractInt(req, "maxDepth", 40));
        Eq("bool false", false, Json.ExtractBool(req, "hideFromCapture", true));
        Eq("bool fallback", true, Json.ExtractBool(req, "nothere", true));
        Eq("path is unescaped", "C:\\Users\\Jos\u00e9\\AppData\\Local\\UIScope\\tmp", Json.ExtractString(req, "tmpDir"));
        Eq("a value equal to a key name is not mistaken for the key", "7", Json.ExtractNumber("{\"type\":\"id\",\"id\":7}", "id"));
        Eq("compact form", "3", Json.ExtractNumber("{\"id\":3,\"type\":\"ping\"}", "id"));
        Eq("string id is not a number", null, Json.ExtractNumber("{\"id\":\"x\"}", "id"));
        Eq("unterminated string", null, Json.ExtractString("{\"type\":\"abc", "type"));

        Eq("P is 0x50 (it used to be F1)", 0x50u, Program.KeyToVk("P"));
        Eq("lower-case p", 0x50u, Program.KeyToVk("p"));
        Eq("digit", 0x35u, Program.KeyToVk("5"));
        Eq("F5", 0x74u, Program.KeyToVk("F5"));
        Eq("F24", 0x87u, Program.KeyToVk("f24"));
        Eq("F25 is not a key", 0u, Program.KeyToVk("f25"));
        Eq("space", 0x20u, Program.KeyToVk("Space"));
        Eq("unknown", 0u, Program.KeyToVk("banana"));
        Eq("empty", 0u, Program.KeyToVk(""));
        Console.WriteLine(failed == 0 ? "ALL OK" : failed + " FAILED");
        return failed;
    }
}
