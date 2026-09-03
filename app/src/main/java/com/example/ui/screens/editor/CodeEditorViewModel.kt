package com.example.ui.screens.editor

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

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

    private var diagnosticJob: Job? = null
    var isAutoDiagnosticsEnabled = true

    fun updateContent(newContent: TextFieldValue) {
        val index = _currentTabIndex.value
        val tab = _tabs.value[index]
        
        val newUndoStack = if (tab.content.text != newContent.text) {
            tab.undoStack.takeLast(20) + tab.content
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
    
    private fun queueDiagnostics(text: String, filename: String) {
        diagnosticJob?.cancel()
        diagnosticJob = viewModelScope.launch {
            delay(400) // 400ms debounce
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
            } else if (file.length() > 2 * 1024 * 1024) { // > 2MB safe truncation
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
        
        // If current only tab is clean empty untitled, replace it
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
        } else {
            // Reset the single tab to a clean untitled state
            _tabs.value = listOf(EditorTab())
            _currentTabIndex.value = 0
            _diagnostics.value = "Ready"
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
        if (filename.endsWith(".json")) {
            _diagnostics.value = validateJson(text)
        } else if (filename.endsWith(".xml")) {
            _diagnostics.value = validateXml(text)
        } else {
            _diagnostics.value = "No offline diagnostics available for this file type."
        }
    }
    
    private fun validateJson(text: String): String {
        if (text.isBlank()) return "Valid JSON (empty)"
        // Simple manual validation for basic syntax issues
        var openBraces = 0
        var openBrackets = 0
        for (c in text) {
            if (c == '{') openBraces++
            if (c == '}') openBraces--
            if (c == '[') openBrackets++
            if (c == ']') openBrackets--
        }
        if (openBraces != 0) return "Syntax Error: Unmatched braces {}"
        if (openBrackets != 0) return "Syntax Error: Unmatched brackets []"
        return "Valid JSON structure (heuristic)"
    }
    
    private fun validateXml(text: String): String {
        if (text.isBlank()) return "Valid XML (empty)"
        var openTags = 0
        var closeTags = 0
        val tagPattern = Regex("<[^>]+>")
        val tags = tagPattern.findAll(text).toList()
        for (match in tags) {
            val tag = match.value
            if (tag.startsWith("</")) closeTags++
            else if (!tag.endsWith("/>") && !tag.startsWith("<?") && !tag.startsWith("<!")) openTags++
        }
        if (openTags != closeTags) return "Syntax Warning: Mismatched tag counts ($openTags open, $closeTags close)"
        return "Valid XML structure (heuristic)"
    }
}
