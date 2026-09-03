package com.example.ui.screens.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class SyntaxHighlighter(private val fileExtension: String, private val searchQuery: String = "") : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val str = text.text
        val annotated = buildAnnotatedString {
            append(str)
            val ext = fileExtension.lowercase()
            
            val (keywords, commentSyntax) = when (ext) {
                "js", "ts" -> listOf("const", "let", "var", "function", "class", "import", "export", "if", "else", "for", "while", "return", "async", "await", "true", "false", "null", "undefined") to listOf("//", "/*")
                "py" -> listOf("def", "class", "import", "from", "if", "elif", "else", "for", "while", "return", "True", "False", "None", "self", "try", "except") to listOf("#", "\"\"\"")
                "json" -> emptyList<String>() to emptyList()
                "sh", "yaml", "yml" -> listOf("if", "then", "else", "fi", "for", "while", "do", "done", "echo", "return", "true", "false") to listOf("#", null)
                else -> listOf("val", "var", "fun", "class", "interface", "import", "package", "public", "private", "protected", "if", "else", "for", "while", "return", "true", "false", "null") to listOf("//", "/*")
            }
            
            // HTML/XML
            if (ext == "html" || ext == "xml") {
                Regex("<\\/?([\\w:-]+)[^>]*>").findAll(str).forEach { match ->
                    addStyle(SpanStyle(color = Color(0xFFE8BF6A)), match.range.first, match.range.last + 1)
                }
                Regex("([\\w:-]+)=").findAll(str).forEach { match ->
                    addStyle(SpanStyle(color = Color(0xFFBABABA)), match.range.first, match.range.last + 1)
                }
                Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).findAll(str).forEach { match ->
                    addStyle(SpanStyle(color = Color(0xFF808080)), match.range.first, match.range.last + 1)
                }
            } else if (ext == "css") {
                Regex("([\\w-]+)\\s*:").findAll(str).forEach { match ->
                    addStyle(SpanStyle(color = Color(0xFF9876AA)), match.groups[1]!!.range.first, match.groups[1]!!.range.last + 1)
                }
                Regex("([\\.\\#\\w]+)\\s*\\{").findAll(str).forEach { match ->
                    addStyle(SpanStyle(color = Color(0xFFFFC66D)), match.groups[1]!!.range.first, match.groups[1]!!.range.last + 1)
                }
                Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(str).forEach { match ->
                    addStyle(SpanStyle(color = Color(0xFF808080)), match.range.first, match.range.last + 1)
                }
            } else if (ext == "md") {
                Regex("^#{1,6}\\s.*", RegexOption.MULTILINE).findAll(str).forEach { match ->
                    addStyle(SpanStyle(color = Color(0xFF56A8F5)), match.range.first, match.range.last + 1)
                }
                Regex("(\\*\\*.*?\\*\\*|__.*?__)").findAll(str).forEach { match ->
                    addStyle(SpanStyle(color = Color(0xFFCC7832)), match.range.first, match.range.last + 1)
                }
                Regex("```.*?```", RegexOption.DOT_MATCHES_ALL).findAll(str).forEach { match ->
                    addStyle(SpanStyle(color = Color(0xFF6A8759)), match.range.first, match.range.last + 1)
                }
            } else {
                if (keywords.isNotEmpty()) {
                    val keywordRegex = Regex("\\b(${keywords.joinToString("|")})\\b")
                    keywordRegex.findAll(str).forEach { match ->
                        addStyle(SpanStyle(color = Color(0xFFCC7832)), match.range.first, match.range.last + 1)
                    }
                }
                
                // Numbers & booleans for JSON
                if (ext == "json") {
                    Regex("\\b(true|false|null|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b").findAll(str).forEach { match ->
                        addStyle(SpanStyle(color = Color(0xFFCC7832)), match.range.first, match.range.last + 1)
                    }
                    Regex("\"(.*?)\"\\s*:").findAll(str).forEach { match ->
                        addStyle(SpanStyle(color = Color(0xFF9876AA)), match.groups[1]!!.range.first, match.groups[1]!!.range.last + 1)
                    }
                }
                
                val stringRegex = Regex("\".*?\"|'.*?'")
                stringRegex.findAll(str).forEach { match ->
                    addStyle(SpanStyle(color = Color(0xFF6A8759)), match.range.first, match.range.last + 1)
                }
                
                val lineComment = commentSyntax.getOrNull(0)
                val blockCommentStart = commentSyntax.getOrNull(1)
                
                if (lineComment != null) {
                    val commentRegex = Regex("$lineComment.*")
                    commentRegex.findAll(str).forEach { match ->
                        addStyle(SpanStyle(color = Color(0xFF808080)), match.range.first, match.range.last + 1)
                    }
                }
                
                if (blockCommentStart == "/*") {
                    Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(str).forEach { match ->
                        addStyle(SpanStyle(color = Color(0xFF808080)), match.range.first, match.range.last + 1)
                    }
                }
                if (blockCommentStart == "\"\"\"") {
                    Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL).findAll(str).forEach { match ->
                        addStyle(SpanStyle(color = Color(0xFF808080)), match.range.first, match.range.last + 1)
                    }
                }
            }
            
            // Search highlighting
            if (searchQuery.isNotEmpty()) {
                var index = str.indexOf(searchQuery, ignoreCase = true)
                while (index >= 0) {
                    addStyle(SpanStyle(background = Color(0x66FFEB3B)), index, index + searchQuery.length)
                    index = str.indexOf(searchQuery, index + searchQuery.length, ignoreCase = true)
                }
            }
        }
        
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
