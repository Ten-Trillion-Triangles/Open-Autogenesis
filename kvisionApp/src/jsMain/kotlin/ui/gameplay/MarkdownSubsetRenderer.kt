package ui.gameplay

import io.kvision.core.WhiteSpace
import io.kvision.html.code
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.li
import io.kvision.html.p
import io.kvision.html.ul
import io.kvision.html.Div

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class UnorderedList(val items: List<String>) : MarkdownBlock()
    data class CodeFence(val text: String) : MarkdownBlock()
}

object MarkdownSubsetRenderer {
    fun parse(text: String): List<MarkdownBlock> {
        if (text.isBlank()) {
            return emptyList()
        }

        val lines = text.lines()
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraphBuffer = mutableListOf<String>()
        val listBuffer = mutableListOf<String>()
        val codeBuffer = mutableListOf<String>()

        var inCodeFence = false

        fun flushParagraph() {
            if (paragraphBuffer.isEmpty()) {
                return
            }
            blocks.add(MarkdownBlock.Paragraph(paragraphBuffer.joinToString("\n").trim()))
            paragraphBuffer.clear()
        }

        fun flushList() {
            if (listBuffer.isEmpty()) {
                return
            }
            blocks.add(MarkdownBlock.UnorderedList(listBuffer.toList()))
            listBuffer.clear()
        }

        fun flushCodeFence() {
            if (codeBuffer.isEmpty()) {
                blocks.add(MarkdownBlock.CodeFence(""))
                return
            }
            blocks.add(MarkdownBlock.CodeFence(codeBuffer.joinToString("\n")))
            codeBuffer.clear()
        }

        for (line in lines) {
            val trimmed = line.trimEnd()

            if (trimmed.startsWith("```")) {
                if (!inCodeFence) {
                    flushParagraph()
                    flushList()
                    inCodeFence = true
                    continue
                }

                inCodeFence = false
                flushCodeFence()
                continue
            }

            if (inCodeFence) {
                codeBuffer.add(trimmed)
                continue
            }

            if (trimmed.isBlank()) {
                flushParagraph()
                flushList()
                continue
            }

            if (trimmed.startsWith("### ")) {
                flushParagraph()
                flushList()
                blocks.add(MarkdownBlock.Heading(3, trimmed.removePrefix("### ").trim()))
                continue
            }

            if (trimmed.startsWith("## ")) {
                flushParagraph()
                flushList()
                blocks.add(MarkdownBlock.Heading(2, trimmed.removePrefix("## ").trim()))
                continue
            }

            if (trimmed.startsWith("# ")) {
                flushParagraph()
                flushList()
                blocks.add(MarkdownBlock.Heading(1, trimmed.removePrefix("# ").trim()))
                continue
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushParagraph()
                listBuffer.add(trimmed.drop(2).trim())
                continue
            }

            flushList()
            paragraphBuffer.add(trimmed)
        }

        if (inCodeFence) {
            flushCodeFence()
        }

        flushParagraph()
        flushList()

        return blocks
    }

    fun buildPanel(text: String): Div {
        val blocks = parse(text)
        return Div().apply {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> {
                        add(
                            when (block.level) {
                                1 -> h1(block.text)
                                2 -> h2(block.text)
                                else -> h3(block.text)
                            }
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        add(p(block.text))
                    }

                    is MarkdownBlock.UnorderedList -> {
                        add(
                            ul {
                                block.items.forEach { item ->
                                    li(item)
                                }
                            }
                        )
                    }

                    is MarkdownBlock.CodeFence -> {
                        add(
                            Div().apply {
                                add(code(block.text))
                                whiteSpace = WhiteSpace.PREWRAP
                            }
                        )
                    }
                }
            }
        }
    }
}