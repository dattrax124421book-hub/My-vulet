package com.example.ui.screens.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

class CodeEditorViewModelTest {

    @Test
    fun testUndoRedoStack() {
        val viewModel = CodeEditorViewModel()
        
        // Initial state
        val initialText = viewModel.tabs.value[0].content.text
        assertEquals("", initialText)
        
        // Update content
        viewModel.updateContent(TextFieldValue("A"))
        assertEquals("A", viewModel.tabs.value[0].content.text)
        
        viewModel.updateContent(TextFieldValue("AB"))
        assertEquals("AB", viewModel.tabs.value[0].content.text)
        
        // Test Undo
        viewModel.undo()
        assertEquals("A", viewModel.tabs.value[0].content.text)
        
        viewModel.undo()
        assertEquals("", viewModel.tabs.value[0].content.text)
        
        // Test Redo
        viewModel.redo()
        assertEquals("A", viewModel.tabs.value[0].content.text)
        
        // New edit clears redo stack
        viewModel.updateContent(TextFieldValue("AC"))
        assertEquals("AC", viewModel.tabs.value[0].content.text)
        
        viewModel.redo()
        assertEquals("AC", viewModel.tabs.value[0].content.text) // Should not change
    }

    @Test
    fun testAutoIndentAfterOpenBrace() {
        val viewModel = CodeEditorViewModel()
        // Line ends with '{'
        viewModel.updateContent(TextFieldValue("fun test() {", TextRange(12)))
        
        // Simulating pressing Enter at the end of "fun test() {"
        val input = TextFieldValue("fun test() {\n", TextRange(13))
        val result = viewModel.handleTextChangeWithAutoIndent(input)
        
        // Should auto-indent with 4 spaces
        assertEquals("fun test() {\n    ", result.text)
        assertEquals(17, result.selection.start)
    }

    @Test
    fun testJumpToLine() {
        val viewModel = CodeEditorViewModel()
        val multiLineText = "line 1\nline 2\nline 3\nline 4"
        viewModel.updateContent(TextFieldValue(multiLineText, TextRange(0)))
        
        viewModel.jumpToLine(3)
        // Cursor selection should be placed at the start of line 3 ("line 1\nline 2\n" -> index 14)
        val selection = viewModel.tabs.value[0].content.selection
        assertEquals(14, selection.start)
        assertEquals(14, selection.end)
    }
}

