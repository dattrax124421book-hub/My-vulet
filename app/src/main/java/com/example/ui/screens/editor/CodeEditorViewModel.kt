package com.example.ui.screens.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.StringReader

data class DiagnosticIssue(
    val line: Int,
    val column: Int = 1,
    val message: String,
    val isError: Boolean = true
)

data class EditorTab(
    val file: File? = null,
    val name: String = "untitled.txt",
    val content: TextFieldValue = TextFieldValue(""),
    val isModified: Boolean = false,
    val undoStack: List<TextFieldValue> = emptyList(),
    val redoStack: List<TextFieldValue> = emptyList()
)

class CodeEditorViewModel : ViewModel() {
    private val _tabs = MutableStateFlow<List<EditorTab>>(listOf(EditorTab()))
    val tabs: StateFlow<List<EditorTab>> = _tabs.asStateFlow()

    private val _currentTabIndex = MutableStateFlow(0)
    val currentTabIndex: StateFlow<Int> = _currentTabIndex.asStateFlow()
    
    private val _diagnostics = MutableStateFlow<String>("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    private val _diagnosticIssues = MutableStateFlow<List<DiagnosticIssue>>(emptyList())
    val diagnosticIssues: StateFlow<List<DiagnosticIssue>> = _diagnosticIssues.asStateFlow()

    private var diagnosticJob: Job? = null
    var isAutoDiagnosticsEnabled = true

    fun updateContent(newContent: TextFieldValue) {
        val index = _currentTabIndex.value
        val tab = _tabs.value[index]
        
        val newUndoStack = if (tab.content.text != newContent.text) {
            tab.undoStack.takeLast(25) + tab.content
        } else {
            tab.undoStack
        }
        
        val updatedTab = tab.copy(
            content = newContent, 
            isModified = true,
            undoStack = newUndoStack,
            redoStack = emptyList()
        )
        
        val newTabs = _tabs.value.toMutableList()
        newTabs[index] = updatedTab
        _tabs.value = newTabs
        
        if (isAutoDiagnosticsEnabled) {
            queueDiagnostics(newContent.text, updatedTab.name)
        }
    }

    fun handleTextChangeWithAutoIndent(newContent: TextFieldValue): TextFieldValue {
        val index = _currentTabIndex.value
        val oldTab = _tabs.value.getOrNull(index) ?: return newContent
        val oldText = oldTab.content.text
        val newText = newContent.text

        val cursor = newContent.selection.start
        if (newText.length == oldText.length + 1 && cursor > 0 && newText[cursor - 1] == '\n') {
            val prevLineEnd = cursor - 1
            val prevLineStart = newText.lastIndexOf('\n', prevLineEnd - 1) + 1
            val prevLine = newText.substring(prevLineStart, prevLineEnd)
            
            val leadingSpaces = prevLine.takeWhile { it == ' ' || it == '\t' }
            val shouldExtraIndent = prevLine.trimEnd().let { 
                it.endsWith("{") || it.endsWith("(") || it.endsWith("[") || it.endsWith(":") 
            }
            val extraIndent = if (shouldExtraIndent) "    " else ""
            val fullIndent = leadingSpaces + extraIndent

            if (fullIndent.isNotEmpty()) {
                val insertedText = newText.substring(0, cursor) + fullIndent + newText.substring(cursor)
                val newCursor = cursor + fullIndent.length
                return TextFieldValue(insertedText, selection = TextRange(newCursor, newCursor))
            }
        }
        return newContent
    }

    fun insertSymbol(symbol: String) {
        val index = _currentTabIndex.value
        val tab = _tabs.value.getOrNull(index) ?: return
        val text = tab.content.text
        val sel = tab.content.selection
        val start = sel.min
        val end = sel.max
        val newText = text.substring(0, start) + symbol + text.substring(end)
        val newPos = start + symbol.length
        updateContent(TextFieldValue(newText, selection = TextRange(newPos, newPos)))
    }

    fun jumpToLine(lineNumber: Int) {
        val index = _currentTabIndex.value
        val tab = _tabs.value.getOrNull(index) ?: return
        val lines = tab.content.text.lines()
        val target = lineNumber.coerceIn(1, maxOf(1, lines.size))
        var offset = 0
        for (i in 0 until target - 1) {
            offset += lines[i].length + 1
        }
        val targetOffset = offset.coerceIn(0, tab.content.text.length)
        updateContent(tab.content.copy(selection = TextRange(targetOffset, targetOffset)))
    }
    
    private fun queueDiagnostics(text: String, filename: String) {
        diagnosticJob?.cancel()
        diagnosticJob = viewModelScope.launch {
            delay(350) // 350ms debounce
            runDiagnostics(text, filename)
        }
    }
    
    fun manualRecheck() {
        val index = _currentTabIndex.value
        val tab = _tabs.value[index]
        runDiagnostics(tab.content.text, tab.name)
    }
    
    fun undo() {
        val index = _currentTabIndex.value
        val tab = _tabs.value[index]
        if (tab.undoStack.isNotEmpty()) {
            val prevState = tab.undoStack.last()
            val newUndoStack = tab.undoStack.dropLast(1)
            val newRedoStack = tab.redoStack + tab.content
            
            val updatedTab = tab.copy(
                content = prevState,
                undoStack = newUndoStack,
                redoStack = newRedoStack,
                isModified = true
            )
            val newTabs = _tabs.value.toMutableList()
            newTabs[index] = updatedTab
            _tabs.value = newTabs
            if (isAutoDiagnosticsEnabled) {
                queueDiagnostics(prevState.text, tab.name)
            }
        }
    }
    
    fun redo() {
        val index = _currentTabIndex.value
        val tab = _tabs.value[index]
        if (tab.redoStack.isNotEmpty()) {
            val nextState = tab.redoStack.last()
            val newRedoStack = tab.redoStack.dropLast(1)
            val newUndoStack = tab.undoStack + tab.content
            
            val updatedTab = tab.copy(
                content = nextState,
                undoStack = newUndoStack,
                redoStack = newRedoStack,
                isModified = true
            )
            val newTabs = _tabs.value.toMutableList()
            newTabs[index] = updatedTab
            _tabs.value = newTabs
            if (isAutoDiagnosticsEnabled) {
                queueDiagnostics(nextState.text, tab.name)
            }
        }
    }

    fun addTab(file: File? = null) {
        if (file != null) {
            val existingIndex = _tabs.value.indexOfFirst { it.file?.absolutePath == file.absolutePath }
            if (existingIndex >= 0) {
                switchTab(existingIndex)
                return
            }
        }

        val name = file?.name ?: "untitled-${_tabs.value.size + 1}.txt"
        val text = try {
            if (file == null || !file.exists() || !file.canRead() || file.isDirectory) {
                ""
            } else if (file.length() > 2 * 1024 * 1024) {
                file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(150 * 1024)
                    val read = reader.read(buffer, 0, buffer.size)
                    if (read > 0) String(buffer, 0, read) + "\n\n--- [Preview Truncated: File exceeds 2MB] ---"
                    else ""
                }
            } else {
                try {
                    file.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    file.readText(Charsets.ISO_8859_1)
                }
            }
        } catch (t: Throwable) {
            "/* Error reading file as text: ${t.localizedMessage ?: "Unsupported or inaccessible file"} */"
        }
        val newTab = EditorTab(file = file, name = name, content = TextFieldValue(text))
        
        if (_tabs.value.size == 1 && _tabs.value[0].file == null && _tabs.value[0].content.text.isEmpty() && !_tabs.value[0].isModified) {
            _tabs.value = listOf(newTab)
            _currentTabIndex.value = 0
        } else {
            _tabs.value = _tabs.value + newTab
            _currentTabIndex.value = _tabs.value.lastIndex
        }

        if (isAutoDiagnosticsEnabled) {
            runDiagnostics(newTab.content.text, newTab.name)
        }
    }
    
    fun switchTab(index: Int) {
        if (index in _tabs.value.indices) {
            _currentTabIndex.value = index
            val tab = _tabs.value[index]
            if (isAutoDiagnosticsEnabled) {
                runDiagnostics(tab.content.text, tab.name)
            } else {
                _diagnostics.value = "Diagnostics off"
                _diagnosticIssues.value = emptyList()
            }
        }
    }

    fun closeTab(index: Int) {
        if (_tabs.value.size > 1) {
            val newTabs = _tabs.value.toMutableList()
            newTabs.removeAt(index)
            _tabs.value = newTabs
            if (_currentTabIndex.value >= newTabs.size) {
                _currentTabIndex.value = newTabs.lastIndex
            }
            val activeTab = _tabs.value[_currentTabIndex.value]
            runDiagnostics(activeTab.content.text, activeTab.name)
        } else {
            _tabs.value = listOf(EditorTab())
            _currentTabIndex.value = 0
            _diagnostics.value = "Ready"
            _diagnosticIssues.value = emptyList()
        }
    }

    fun saveCurrentFile(fallbackDir: File): Boolean {
        val index = _currentTabIndex.value
        val tab = _tabs.value.getOrNull(index) ?: return false
        return try {
            val file = tab.file ?: File(fallbackDir, tab.name)
            file.parentFile?.mkdirs()
            file.writeText(tab.content.text, Charsets.UTF_8)
            
            val newTabs = _tabs.value.toMutableList()
            newTabs[index] = tab.copy(file = file, name = file.name, isModified = false)
            _tabs.value = newTabs
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun saveFileAs(fileName: String, targetDir: File): Boolean {
        val index = _currentTabIndex.value
        val tab = _tabs.value.getOrNull(index) ?: return false
        val trimmed = fileName.trim()
        if (trimmed.isEmpty()) return false
        return try {
            targetDir.mkdirs()
            val file = File(targetDir, trimmed)
            file.writeText(tab.content.text, Charsets.UTF_8)

            val newTabs = _tabs.value.toMutableList()
            newTabs[index] = tab.copy(file = file, name = file.name, isModified = false)
            _tabs.value = newTabs
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun canUndo(): Boolean {
        val tab = _tabs.value.getOrNull(_currentTabIndex.value) ?: return false
        return tab.undoStack.isNotEmpty()
    }

    fun canRedo(): Boolean {
        val tab = _tabs.value.getOrNull(_currentTabIndex.value) ?: return false
        return tab.redoStack.isNotEmpty()
    }

    fun getDetectedLanguage(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "Kotlin"
            "java" -> "Java"
            "json" -> "JSON"
            "xml" -> "XML"
            "html", "htm" -> "HTML"
            "css" -> "CSS"
            "js" -> "JavaScript"
            "ts" -> "TypeScript"
            "py" -> "Python"
            "sh", "bash" -> "Shell"
            "c", "h" -> "C"
            "cpp", "hpp" -> "C++"
            "sql" -> "SQL"
            "md" -> "Markdown"
            "yaml", "yml" -> "YAML"
            "txt" -> "Plain Text"
            else -> if (ext.isNotEmpty()) ext.uppercase() else "Plain Text"
        }
    }
    
    private fun runDiagnostics(text: String, filename: String) {
        val ext = filename.substringAfterLast('.', "").lowercase()
        val issues = when (ext) {
            "json" -> validateJson(text)
            "xml", "html", "svg" -> validateXml(text)
            "py" -> validateBracketsAndStrings(text, isPython = true)
            "kt", "kts", "java", "js", "ts", "c", "cpp", "h", "hpp", "cs", "rs", "go" -> validateBracketsAndStrings(text, isPython = false)
            else -> validateBracketsAndStrings(text, isPython = false)
        }

        _diagnosticIssues.value = issues

        val errors = issues.count { it.isError }
        val warnings = issues.count { !it.isError }
        _diagnostics.value = when {
            issues.isEmpty() -> "No syntax errors"
            errors > 0 && warnings > 0 -> "$errors error(s), $warnings warning(s)"
            errors > 0 -> "$errors error(s)"
            else -> "$warnings warning(s)"
        }
    }
    
    private fun validateJson(text: String): List<DiagnosticIssue> {
        if (text.isBlank()) return emptyList()
        val issues = mutableListOf<DiagnosticIssue>()
        val trimmed = text.trim()
        try {
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed)
            } else if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                issues.add(DiagnosticIssue(line = 1, message = "JSON root must be an object '{' or array '['", isError = true))
                return issues
            }
        } catch (e: JSONException) {
            val msg = e.message ?: "JSON Syntax Error"
            val match = Regex("at character (\\d+)").find(msg)
            val charPos = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val line = if (charPos > 0 && charPos <= text.length) {
                text.take(charPos).count { it == '\n' } + 1
            } else 1
            issues.add(DiagnosticIssue(line = line, message = msg, isError = true))
        }

        val bracketIssues = validateBracketsAndStrings(text)
        for (b in bracketIssues) {
            if (issues.none { it.line == b.line }) {
                issues.add(b)
            }
        }
        return issues
    }
    
    private fun validateXml(text: String): List<DiagnosticIssue> {
        if (text.isBlank()) return emptyList()
        val issues = mutableListOf<DiagnosticIssue>()
        try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(StringReader(text))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next()
            }
        } catch (e: XmlPullParserException) {
            val line = if (e.lineNumber > 0) e.lineNumber else 1
            val col = if (e.columnNumber > 0) e.columnNumber else 1
            val cleanMsg = e.message?.substringBefore("(position:")?.trim() ?: "XML Syntax Error"
            issues.add(DiagnosticIssue(line = line, column = col, message = cleanMsg, isError = true))
        } catch (e: Exception) {
            issues.add(DiagnosticIssue(line = 1, message = e.message ?: "Invalid XML", isError = true))
        }
        return issues
    }

    private fun validateBracketsAndStrings(text: String, isPython: Boolean = false): List<DiagnosticIssue> {
        if (text.isBlank()) return emptyList()
        val issues = mutableListOf<DiagnosticIssue>()
        val lines = text.lines()
        val stack = ArrayDeque<Triple<Char, Int, Int>>() // char, line, col

        for ((lineIdx, lineStr) in lines.withIndex()) {
            val lineNum = lineIdx + 1
            var inSingleQuote = false
            var inDoubleQuote = false
            var isEscaped = false

            for (colIdx in lineStr.indices) {
                val c = lineStr[colIdx]
                val colNum = colIdx + 1

                if (isEscaped) {
                    isEscaped = false
                    continue
                }
                if (c == '\\') {
                    isEscaped = true
                    continue
                }

                if (c == '"' && !inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote
                    continue
                }
                if (c == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote
                    continue
                }

                if (!inSingleQuote && !inDoubleQuote) {
                    if (c == '/' && colIdx + 1 < lineStr.length && lineStr[colIdx + 1] == '/') {
                        break
                    }
                    if (isPython && c == '#') {
                        break
                    }
                }

                if (!inSingleQuote && !inDoubleQuote) {
                    when (c) {
                        '{', '(', '[' -> stack.addLast(Triple(c, lineNum, colNum))
                        '}' -> {
                            if (stack.isEmpty()) {
                                issues.add(DiagnosticIssue(lineNum, colNum, "Unexpected closing '}' with no matching opening '{'", true))
                            } else {
                                val top = stack.removeLast()
                                if (top.first != '{') {
                                    issues.add(DiagnosticIssue(lineNum, colNum, "Mismatched '}' (expected matching '${matching(top.first)}' opened at line ${top.second})", true))
                                }
                            }
                        }
                        ')' -> {
                            if (stack.isEmpty()) {
                                issues.add(DiagnosticIssue(lineNum, colNum, "Unexpected closing ')' with no matching opening '('", true))
                            } else {
                                val top = stack.removeLast()
                                if (top.first != '(') {
                                    issues.add(DiagnosticIssue(lineNum, colNum, "Mismatched ')' (expected matching '${matching(top.first)}' opened at line ${top.second})", true))
                                }
                            }
                        }
                        ']' -> {
                            if (stack.isEmpty()) {
                                issues.add(DiagnosticIssue(lineNum, colNum, "Unexpected closing ']' with no matching opening '['", true))
                            } else {
                                val top = stack.removeLast()
                                if (top.first != '[') {
                                    issues.add(DiagnosticIssue(lineNum, colNum, "Mismatched ']' (expected matching '${matching(top.first)}' opened at line ${top.second})", true))
                                }
                            }
                        }
                    }
                }
            }

            if (inDoubleQuote && !lineStr.contains("\"\"\"")) {
                issues.add(DiagnosticIssue(lineNum, lineStr.length, "Unclosed string literal \"", false))
            } else if (inSingleQuote && !lineStr.contains("'''")) {
                issues.add(DiagnosticIssue(lineNum, lineStr.length, "Unclosed character/string '", false))
            }

            if (isPython) {
                val trimmed = lineStr.trim()
                val colonKeywords = listOf("def ", "class ", "if ", "elif ", "else:", "for ", "while ", "try:", "except")
                if (colonKeywords.any { trimmed.startsWith(it) } && !trimmed.endsWith(":") && !trimmed.endsWith("{") && !trimmed.contains("#")) {
                    issues.add(DiagnosticIssue(lineNum, lineStr.length, "Missing ':' at end of statement", false))
                }
            }
        }

        while (stack.isNotEmpty()) {
            val top = stack.removeLast()
            issues.add(DiagnosticIssue(top.second, top.third, "Unclosed '${top.first}' opened at line ${top.second}", true))
        }

        return issues
    }

    private fun matching(c: Char): Char = when (c) {
        '{' -> '}'
        '(' -> ')'
        '[' -> ']'
        else -> ' '
    }
}

