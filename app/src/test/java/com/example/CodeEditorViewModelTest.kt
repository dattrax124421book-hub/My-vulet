package com.example.ui.screens.editor

import org.junit.Assert.assertEquals
import org.junit.Test
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
}
