using System;
using System.Globalization;
using System.Text;

namespace UIScope.Helper
{
    /// <summary>
    /// The few JSON shapes of the protocol are written by hand (no serializer, no dependency). Everything that ends up inside a string goes
    /// through <see cref="Str"/>, which escapes <b>every</b> control character: the Kotlin reader is strict and rejects a raw one, which used
    /// to drop a whole window list whenever a single title contained e.g. a backspace.
    /// </summary>
    internal static class Json
    {
        public static string Str(string s)
        {
            if (s == null) return "\"\"";
            var sb = new StringBuilder(s.Length + 2);
            sb.Append('"');
            foreach (char c in s)
            {
                switch (c)
                {
                    case '\\': sb.Append("\\\\"); break;
                    case '"': sb.Append("\\\""); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    case '\b': sb.Append("\\b"); break;
                    case '\f': sb.Append("\\f"); break;
                    default:
                        if (c < ' ' || c == '\u2028' || c == '\u2029') sb.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                        else sb.Append(c);
                        break;
                }
            }
            return sb.Append('"').ToString();
        }

        public static string Bool(bool value)
        {
            return value ? "true" : "false";
        }

        public static string Rect(int x, int y, int width, int height)
        {
            return "{\"x\":" + x.ToString(CultureInfo.InvariantCulture) + ",\"y\":" + y.ToString(CultureInfo.InvariantCulture) +
                   ",\"width\":" + width.ToString(CultureInfo.InvariantCulture) + ",\"height\":" + height.ToString(CultureInfo.InvariantCulture) + "}";
        }

        public static string Hwnd(IntPtr hwnd)
        {
            return "0x" + hwnd.ToInt64().ToString("x8", CultureInfo.InvariantCulture);
        }

        // -- reading the flat request objects the Kotlin client sends ------------------------------

        /// <summary>The index of the value that follows <c>"key":</c> (whitespace tolerated), or -1. Only a key followed by a colon counts, so a value that merely equals the key is skipped.</summary>
        private static int ValueStart(string json, string key)
        {
            string needle = "\"" + key + "\"";
            int from = 0;
            while (from < json.Length)
            {
                int at = json.IndexOf(needle, from, StringComparison.Ordinal);
                if (at < 0) return -1;
                int i = at + needle.Length;
                while (i < json.Length && char.IsWhiteSpace(json[i])) i++;
                if (i < json.Length && json[i] == ':')
                {
                    i++;
                    while (i < json.Length && char.IsWhiteSpace(json[i])) i++;
                    return i;
                }
                from = at + 1;
            }
            return -1;
        }

        /// <summary>A string value with its JSON escapes resolved (a Windows path arrives as <c>C:\\Users\\...</c>); null when absent or not a string.</summary>
        public static string ExtractString(string json, string key)
        {
            int i = ValueStart(json, key);
            if (i < 0 || i >= json.Length || json[i] != '"') return null;
            i++;
            var sb = new StringBuilder();
            while (i < json.Length)
            {
                char c = json[i++];
                if (c == '"') return sb.ToString();
                if (c != '\\') { sb.Append(c); continue; }
                if (i >= json.Length) return null;
                char e = json[i++];
                switch (e)
                {
                    case 'n': sb.Append('\n'); break;
                    case 'r': sb.Append('\r'); break;
                    case 't': sb.Append('\t'); break;
                    case 'b': sb.Append('\b'); break;
                    case 'f': sb.Append('\f'); break;
                    case 'u':
                        int code;
                        if (i + 4 > json.Length || !int.TryParse(json.Substring(i, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out code)) return null;
                        sb.Append((char)code);
                        i += 4;
                        break;
                    default: sb.Append(e); break; // \\ \" \/
                }
            }
            return null; // unterminated
        }

        /// <summary>A number value as its literal text (the correlation id is echoed back untouched); null when absent or not a number.</summary>
        public static string ExtractNumber(string json, string key)
        {
            int i = ValueStart(json, key);
            if (i < 0) return null;
            int start = i;
            while (i < json.Length && (char.IsDigit(json[i]) || json[i] == '-' || json[i] == '+' || json[i] == '.' || json[i] == 'e' || json[i] == 'E')) i++;
            return i > start ? json.Substring(start, i - start) : null;
        }

        public static int ExtractInt(string json, string key, int fallback)
        {
            string text = ExtractNumber(json, key);
            long value;
            if (text == null || !long.TryParse(text, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out value)) return fallback;
            if (value > int.MaxValue) return int.MaxValue;
            return value < int.MinValue ? int.MinValue : (int)value;
        }

        public static bool ExtractBool(string json, string key, bool fallback)
        {
            int i = ValueStart(json, key);
            if (i < 0) return fallback;
            if (string.CompareOrdinal(json, i, "true", 0, 4) == 0) return true;
            if (string.CompareOrdinal(json, i, "false", 0, 5) == 0) return false;
            return fallback;
        }
    }

    /// <summary>A failure with a protocol error code (Appendix J) instead of the generic UIA_UNAVAILABLE.</summary>
    internal sealed class ProtocolException : Exception
    {
        public ProtocolException(string code, string message) : base(message)
        {
            Code = code;
        }

        public string Code { get; private set; }
    }
}
