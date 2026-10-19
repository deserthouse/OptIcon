package io.github.deserthouse.opticon.engine.publish

/**
 * JsonIo — 最小 JSON 扁平对象 writer/reader（F1-1）。
 *
 * 禁用 org.json：android.jar 里是抛异常的 stub，JVM 单测跑不了。
 * 只支持受控场景：扁平对象、字符串值（数值/布尔以字符串形式进出）。
 * PendingOp 的 oldCfg/newCfg 本身是 JSON 字符串——经本类转义后嵌套存储，
 * 读出时还原为原始 JSON 文本。
 */
internal fun jsonEscape(s: String): String = buildString {
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
}

/** 扁平对象 writer；null 写为空串（reader 侧以 isBlank 语义还原） */
internal fun jsonObj(vararg pairs: Pair<String, String?>): String =
    pairs.joinToString(",", "{", "}") { (k, v) -> "\"${jsonEscape(k)}\":\"${jsonEscape(v ?: "")}\"" }

/**
 * 读字符串字段；字段缺失返回 null，存在但为空串返回 ""。
 * 处理标准转义（\" \\ \/ \n \r \t \b \f \uXXXX）。
 */
internal fun jsonGetString(json: String, field: String): String? {
    val needle = "\"${jsonEscape(field)}\":"
    var idx = json.indexOf(needle)
    while (idx != -1) {
        var i = idx + needle.length
        while (i < json.length && json[i].isWhitespace()) i++
        if (i < json.length && json[i] == '"') {
            i++
            val sb = StringBuilder()
            var closed = false
            while (i < json.length) {
                val c = json[i]
                if (c == '"') { closed = true; break }
                if (c == '\\') {
                    i++
                    if (i >= json.length) break
                    when (val e = json[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 4 < json.length) {
                                sb.append(json.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                        }
                        else -> sb.append(e)
                    }
                } else sb.append(c)
                i++
            }
            if (closed) return sb.toString()
        }
        idx = json.indexOf(needle, idx + 1)
    }
    return null
}
