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
        val name = file?.name ?: "untitled-${_tabs.value.size}.txt"
        val text = file?.readText() ?: ""
        val newTab = EditorTab(file = file, name = name, content = TextFieldValue(text))
        _tabs.value = _tabs.value + newTab
        _currentTabIndex.value = _tabs.value.lastIndex
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
        }
    }

    fun saveCurrentFile(filesDir: File): Boolean {
        val index = _currentTabIndex.value
        val tab = _tabs.value[index]
        return try {
            val file = tab.file ?: File(filesDir, tab.name)
            file.writeText(tab.content.text)
            
            val newTabs = _tabs.value.toMutableList()
            newTabs[index] = tab.copy(file = file, isModified = false)
            _tabs.value = newTabs
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
