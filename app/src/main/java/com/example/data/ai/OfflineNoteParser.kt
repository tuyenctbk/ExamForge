package com.example.data.ai

import com.example.data.model.CardType
import com.example.data.model.DeckSourceType
import com.example.data.model.GeneratedCardDraft
import com.example.data.model.GeneratedDeckDraft
import java.util.UUID

/**
 * Zero-cost, 100% offline flashcard generator that converts raw study notes or spoken lecture
 * transcripts into structured active-recall flashcards without calling any paid cloud API.
 *
 * Supports:
 * - "Term: Definition" / "Term - Definition" / "Term = Formula" pairs
 * - Explicit "Q: ... / A: ..." blocks
 * - Bullet lists and dense lecture sentences (auto-converted into Fill-in-the-Blank & Concept cards)
 */
object OfflineNoteParser {

    fun parseNotesOffline(
        rawNotes: String,
        targetCardCount: Int = 6,
        subjectHint: String = "",
        sourceType: DeckSourceType = DeckSourceType.NOTES
    ): ParsedFlashcardBatch {
        val cleaned = rawNotes.trim()
        require(cleaned.isNotBlank()) {
            "Add study notes or record a voice lecture snippet to generate offline flashcards."
        }

        val safeTarget = targetCardCount.coerceIn(3, 12)
        val lines = cleaned
            .replace("\r\n", "\n")
            .split("\n")
            .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ").trim() }
            .filter { it.length >= 6 }

        val extracted = mutableListOf<StructuredFlashcardData>()

        // Pass 1: Explicit Q:/A: pairs across adjacent lines
        var i = 0
        while (i < lines.size - 1 && extracted.size < safeTarget) {
            val line = lines[i]
            val next = lines[i + 1]
            if (line.startsWith("Q:", ignoreCase = true) && next.startsWith("A:", ignoreCase = true)) {
                val q = line.substring(2).trim()
                val a = next.substring(2).trim()
                if (q.isNotBlank() && a.isNotBlank()) {
                    extracted.add(
                        StructuredFlashcardData(
                            cardType = CardType.CONCEPT_EXPLAIN,
                            question = ensureQuestionMark(q),
                            answer = a,
                            explanation = "Generated locally via ExamForge Offline Mode ($0 API cost).",
                            hint = buildWordHint(a),
                            options = emptyList(),
                            correctOptionIndex = -1
                        )
                    )
                    i += 2
                    continue
                }
            }
            i++
        }

        // Pass 2: "Term: Definition", "Term -> Definition", "Term = Formula", "Term - Definition"
        val delimiterRegex = Regex("""^([^:=\-\n]{2,80}?)\s*(?::|->|=>|=|—|–|\s-\s)\s*(.{4,})$""")
        val termPairs = mutableListOf<Pair<String, String>>()
        for (line in lines) {
            if (line.startsWith("Q:", ignoreCase = true) || line.startsWith("A:", ignoreCase = true)) continue
            val match = delimiterRegex.matchEntire(line)
            if (match != null) {
                val term = match.groupValues[1].trim()
                val def = match.groupValues[2].trim()
                if (term.isNotBlank() && def.isNotBlank()) {
                    termPairs.add(term to def)
                }
            }
        }

        // Convert term pairs into a lively mix of MCQ (when enough distractors exist), Fill-in-Blank, and Concept Explain
        termPairs.forEachIndexed { idx, (term, def) ->
            if (extracted.size >= safeTarget) return@forEachIndexed
            val alreadyExists = extracted.any { it.answer.equals(def, ignoreCase = true) || it.question.contains(term, ignoreCase = true) }
            if (alreadyExists) return@forEachIndexed

            val canBuildMcq = termPairs.size >= 4 && idx % 3 == 0
            if (canBuildMcq) {
                val distractors = termPairs
                    .filter { it.first != term && !it.second.equals(def, ignoreCase = true) }
                    .map { it.second.take(180) }
                    .distinct()
                    .take(3)
                if (distractors.size == 3) {
                    val options = (distractors + def.take(180)).shuffled()
                    val correctIdx = options.indexOf(def.take(180)).coerceAtLeast(0)
                    extracted.add(
                        StructuredFlashcardData(
                            cardType = CardType.MULTIPLE_CHOICE,
                            question = "Which best describes $term?",
                            answer = def.take(180),
                            explanation = "$term — $def",
                            hint = "Focus on the core definition of $term.",
                            options = options,
                            correctOptionIndex = correctIdx
                        )
                    )
                    return@forEachIndexed
                }
            }

            if (idx % 2 == 1 && def.length >= 16) {
                extracted.add(
                    StructuredFlashcardData(
                        cardType = CardType.FILL_IN_BLANK,
                        question = "___ refers to: $def",
                        answer = term,
                        explanation = "$term: $def",
                        hint = buildWordHint(term),
                        options = emptyList(),
                        correctOptionIndex = -1
                    )
                )
            } else {
                extracted.add(
                    StructuredFlashcardData(
                        cardType = CardType.CONCEPT_EXPLAIN,
                        question = "Explain or define: $term",
                        answer = def,
                        explanation = "Parsed offline from your study notes.",
                        hint = buildWordHint(def),
                        options = emptyList(),
                        correctOptionIndex = -1
                    )
                )
            }
        }

        // Pass 3: Sentence-level Fill-in-the-Blank extraction for spoken lectures or prose paragraphs
        if (extracted.size < safeTarget) {
            val sentences = cleaned
                .split(Regex("""(?<=[.!?])\s+|\n+"""))
                .map { it.trim() }
                .filter { it.length >= 24 }

            for (sentence in sentences) {
                if (extracted.size >= safeTarget) break
                val keyTerm = pickSalientKeyword(sentence) ?: continue
                val blanked = sentence.replaceFirst(Regex("\\b${Regex.escape(keyTerm)}\\b"), "___")
                if (blanked == sentence) continue
                if (extracted.any { it.question == blanked }) continue

                extracted.add(
                    StructuredFlashcardData(
                        cardType = CardType.FILL_IN_BLANK,
                        question = blanked.take(400),
                        answer = keyTerm,
                        explanation = sentence.take(500),
                        hint = buildWordHint(keyTerm),
                        options = emptyList(),
                        correctOptionIndex = -1
                    )
                )
            }
        }

        // Pass 4: Fallback single summary card if input was very short prose
        if (extracted.isEmpty()) {
            val snippet = cleaned.take(280)
            extracted.add(
                StructuredFlashcardData(
                    cardType = CardType.CONCEPT_EXPLAIN,
                    question = "Recall the key points from your captured study note:",
                    answer = snippet,
                    explanation = "Created locally in Offline Mode so you can edit and refine immediately.",
                    hint = buildWordHint(snippet),
                    options = emptyList(),
                    correctOptionIndex = -1
                )
            )
        }

        val resolvedSubject = subjectHint.trim().ifBlank { "Offline Notes" }.take(60)
        val firstHeading = lines.firstOrNull()?.take(48)?.trimEnd(':', '.', '-', ' ') ?: "Quick Study Notes"
        val deckTitle = if (subjectHint.isNotBlank()) {
            "${subjectHint.trim()} — Quick Deck".take(80)
        } else {
            firstHeading.ifBlank { "Offline Study Deck" }
        }

        val sourceLabel = if (sourceType == DeckSourceType.NOTES) "Offline Instant Parser" else "Offline Mode"
        return ParsedFlashcardBatch(
            deckTitle = deckTitle,
            subject = resolvedSubject,
            summary = "Built locally with $sourceLabel (${extracted.size} cards · zero cloud API cost).",
            flashcards = extracted
        )
    }

    fun toGeneratedDraft(
        rawNotes: String,
        targetCardCount: Int = 6,
        subjectHint: String = ""
    ): GeneratedDeckDraft {
        val batch = parseNotesOffline(
            rawNotes = rawNotes,
            targetCardCount = targetCardCount,
            subjectHint = subjectHint
        )
        return GeneratedDeckDraft(
            title = batch.deckTitle,
            subject = batch.subject,
            summary = batch.summary,
            sourceType = DeckSourceType.NOTES,
            cards = batch.flashcards.map { card ->
                GeneratedCardDraft(
                    localId = UUID.randomUUID().toString(),
                    cardType = card.cardType,
                    question = card.question,
                    answer = card.answer,
                    explanation = card.explanation,
                    hint = card.hint,
                    options = card.options,
                    correctOptionIndex = card.correctOptionIndex
                )
            }
        )
    }

    private fun ensureQuestionMark(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.endsWith("?")) trimmed else "$trimmed?"
    }

    private fun buildWordHint(answer: String): String {
        val firstWord = answer.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return if (firstWord.length >= 2) {
            "Starts with \"${firstWord.take(2)}...\" (${answer.trim().split(Regex("\\s+")).size} word(s))"
        } else {
            "Review the core concept from your notes."
        }
    }

    private fun pickSalientKeyword(sentence: String): String? {
        val stopWords = setOf(
            "about", "above", "after", "again", "against", "because", "before", "being",
            "between", "could", "during", "every", "first", "found", "great", "having",
            "however", "important", "include", "includes", "including", "known", "large",
            "makes", "might", "never", "other", "people", "place", "point", "rather",
            "really", "second", "should", "since", "small", "sometimes", "still", "study",
            "their", "there", "these", "thing", "think", "those", "through", "under",
            "using", "usually", "where", "which", "while", "within", "without", "would"
        )
        val candidates = Regex("""\b[A-Za-z][A-Za-z0-9\-]{4,}\b""")
            .findAll(sentence)
            .map { it.value }
            .filter { it.lowercase() !in stopWords }
            .toList()
        if (candidates.isEmpty()) return null
        // Prefer technical/longer or capitalized mid-sentence terms
        return candidates.maxByOrNull { word ->
            val capBonus = if (word.first().isUpperCase() && !sentence.startsWith(word)) 4 else 0
            word.length + capBonus
        }
    }
}
