package com.example.data.local

import com.example.data.model.CardType
import com.example.data.model.DeckSourceType
import com.example.data.model.GeneratedCardDraft

data class SampleDeckBundle(
    val title: String,
    val subject: String,
    val description: String,
    val sourceType: DeckSourceType,
    val accentIndex: Int,
    val cards: List<SampleCardSeed>
)

data class SampleCardSeed(
    val draft: GeneratedCardDraft,
    val mastered: Boolean = false,
    val reviewedRecently: Boolean = false
)

/**
 * Curated university-grade starter study decks so first-time users, offline learners,
 * and Android TV users can immediately experience Active Recall, 3D Flip Sessions,
 * Match Blitz, and Oral Quizmaster without needing to type notes from scratch.
 */
object SampleStudyDecks {

    const val SAMPLE_FORGE_NOTES = """ATP Synthase: Enzyme complex using a proton gradient across the inner mitochondrial membrane to phosphorylate ADP into ATP.
Oxidative Phosphorylation: Metabolic pathway producing ~30-32 ATP per glucose molecule via the electron transport chain and chemiosmosis.
Krebs Cycle: Eight-step mitochondrial matrix cycle oxidizing acetyl-CoA into CO2, generating NADH and FADH2.
CRISPR-Cas9: RNA-guided endonuclease system enabling precise double-strand DNA cleavage for genome editing.
Allosteric Inhibition: Binding of a regulator molecule at a site other than the active site, changing enzyme conformation and lowering activity.
Michaelis Constant (Km): Substrate concentration at which an enzyme reaches half of its maximum reaction velocity (Vmax/2)."""

    fun defaultBundles(): List<SampleDeckBundle> = listOf(
        SampleDeckBundle(
            title = "Organic Chemistry · Mechanisms",
            subject = "Chemistry",
            description = "High-yield nucleophilic substitution, addition, and pericyclic reactions from Lecture 6.",
            sourceType = DeckSourceType.PHOTO,
            accentIndex = 0,
            cards = listOf(
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "chem_1",
                        cardType = CardType.MULTIPLE_CHOICE,
                        question = "What stereochemical outcome characterizes a concerted SN2 reaction at a chiral center?",
                        answer = "Complete Walden inversion of configuration",
                        explanation = "The nucleophile attacks 180° opposite the leaving group (backside attack), inverting the tetrahedral geometry in one step.",
                        hint = "Think about backside attack geometry.",
                        options = listOf(
                            "Complete Walden inversion of configuration",
                            "50:50 racemic mixture via carbocation",
                            "Retention of stereochemical configuration",
                            "Elimination to a Zaitsev alkene"
                        ),
                        correctOptionIndex = 0
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "chem_2",
                        cardType = CardType.FILL_IN_BLANK,
                        question = "In Markovnikov addition of HBr to an asymmetric alkene, the hydrogen attaches to the carbon with ___ hydrogens.",
                        answer = "more",
                        explanation = "Protonating the less-substituted carbon forms the more stable (more substituted) carbocation intermediate.",
                        hint = "The rich get richer.",
                        options = emptyList(),
                        correctOptionIndex = -1
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "chem_3",
                        cardType = CardType.CONCEPT_EXPLAIN,
                        question = "Why must Grignard reagents (R-MgBr) be prepared in strictly anhydrous ether solvents?",
                        answer = "Grignard reagents are extremely strong bases (pKa ~50) and immediately protonate in water to form alkanes.",
                        explanation = "Even trace moisture destroys R-MgBr via acid-base quenching (R-MgBr + H₂O → R-H + Mg(OH)Br). Ether lone pairs also stabilize Mg²⁺.",
                        hint = "Compare the pKa of an alkane to water.",
                        options = emptyList(),
                        correctOptionIndex = -1
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "chem_4",
                        cardType = CardType.MULTIPLE_CHOICE,
                        question = "Which requirement is essential for a diene to undergo a concerted [4+2] Diels-Alder cycloaddition?",
                        answer = "It must adopt the s-cis conformation",
                        explanation = "Only the s-cis conformation places carbons C1 and C4 close enough for simultaneous orbital overlap with the dienophile.",
                        hint = "Check the rotational geometry around the central C2–C3 bond.",
                        options = listOf(
                            "It must be locked in the s-trans conformation",
                            "It must adopt the s-cis conformation",
                            "It requires UV light activation",
                            "It proceeds through a free-radical chain"
                        ),
                        correctOptionIndex = 1
                    ),
                    mastered = true
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "chem_5",
                        cardType = CardType.CONCEPT_EXPLAIN,
                        question = "Explain why carboxylic acids (pKa ~4.8) are far more acidic than alcohols (pKa ~16).",
                        answer = "The carboxylate conjugate base delocalizes its negative charge equally across two electronegative oxygen atoms via resonance.",
                        explanation = "Resonance stabilization lowers the energy of RCOO⁻ substantially compared to a localized alkoxide ion (RO⁻).",
                        hint = "Draw the conjugate base resonance structures.",
                        options = emptyList(),
                        correctOptionIndex = -1
                    ),
                    mastered = true
                )
            )
        ),
        SampleDeckBundle(
            title = "Calculus II · Integration & Series",
            subject = "Calculus",
            description = "Essential formulas, convergence tests, and Taylor expansions for midterm prep.",
            sourceType = DeckSourceType.NOTES,
            accentIndex = 2,
            cards = listOf(
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "calc_1",
                        cardType = CardType.FILL_IN_BLANK,
                        question = "Integration by parts states that ∫ u dv = u·v − ∫ ___.",
                        answer = "v du",
                        explanation = "Derived directly from reversing the product rule: d(uv) = u dv + v du. Choose u using LIATE priority.",
                        hint = "Swap the differential onto u.",
                        options = emptyList(),
                        correctOptionIndex = -1
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "calc_2",
                        cardType = CardType.MULTIPLE_CHOICE,
                        question = "In the Ratio Test L = lim |aₙ₊₁ / aₙ|, when is the series guaranteed to converge absolutely?",
                        answer = "When L < 1",
                        explanation = "If L < 1 the series converges absolutely; if L > 1 it diverges; if L = 1 the test is inconclusive.",
                        hint = "Think of a geometric series ratio |r|.",
                        options = listOf(
                            "When L = 1",
                            "When L < 1",
                            "When L > 1",
                            "When L = 0 only"
                        ),
                        correctOptionIndex = 1
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "calc_3",
                        cardType = CardType.CONCEPT_EXPLAIN,
                        question = "Write the Maclaurin series expansion for eˣ and state its radius of convergence.",
                        answer = "eˣ = ∑ (xⁿ / n!) for n = 0 to ∞, with radius of convergence R = ∞.",
                        explanation = "Because every derivative of eˣ at x = 0 equals 1, the Taylor coefficients are 1/n!, converging for all real and complex x.",
                        hint = "Every derivative of eˣ is itself.",
                        options = emptyList(),
                        correctOptionIndex = -1
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "calc_4",
                        cardType = CardType.CONCEPT_EXPLAIN,
                        question = "When is L'Hôpital's Rule valid to evaluate lim f(x)/g(x)?",
                        answer = "Only when direct substitution yields an indeterminate form 0/0 or ∞/∞ and g'(x) ≠ 0 near the limit.",
                        explanation = "Applying L'Hôpital's Rule to determinate forms like 1/0 produces incorrect answers. Always check the indeterminate form first.",
                        hint = "Check the two classic indeterminate quotient forms.",
                        options = emptyList(),
                        correctOptionIndex = -1
                    ),
                    mastered = true
                )
            )
        ),
        SampleDeckBundle(
            title = "Cell Biology · Bioenergetics",
            subject = "Biology",
            description = "Mitochondrial ATP production, enzyme kinetics, and molecular genetics.",
            sourceType = DeckSourceType.PHOTO,
            accentIndex = 1,
            cards = listOf(
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "bio_1",
                        cardType = CardType.MULTIPLE_CHOICE,
                        question = "What directly powers the rotor of ATP synthase to phosphorylate ADP into ATP?",
                        answer = "The electrochemical proton (H⁺) gradient across the inner mitochondrial membrane",
                        explanation = "Chemiosmosis drives H⁺ ions down their concentration and electrical gradient from the intermembrane space back into the matrix.",
                        hint = "Peter Mitchell's chemiosmotic hypothesis.",
                        options = listOf(
                            "Direct hydrolysis of NADH at the F₁ subunit",
                            "The electrochemical proton (H⁺) gradient across the inner mitochondrial membrane",
                            "Active sodium-potassium antiport",
                            "Substrate-level phosphorylation in glycolysis"
                        ),
                        correctOptionIndex = 1
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "bio_2",
                        cardType = CardType.FILL_IN_BLANK,
                        question = "During mitosis, sister chromatids align along the cell's equatorial plate during ___.",
                        answer = "metaphase",
                        explanation = "The spindle checkpoint verifies that every kinetochore is attached to microtubules from opposite poles before anaphase begins.",
                        hint = "M for Middle.",
                        options = emptyList(),
                        correctOptionIndex = -1
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "bio_3",
                        cardType = CardType.CONCEPT_EXPLAIN,
                        question = "What is the role of guide RNA (gRNA) in CRISPR-Cas9 gene editing?",
                        answer = "It directs the Cas9 endonuclease to a complementary 20-nucleotide target DNA sequence adjacent to a PAM motif.",
                        explanation = "Base-pairing between gRNA and genomic DNA triggers Cas9 to create a precise double-strand break, enabling knockout or HDR repair.",
                        hint = "Think of gRNA as the GPS address for the molecular scissors.",
                        options = emptyList(),
                        correctOptionIndex = -1
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "bio_4",
                        cardType = CardType.CONCEPT_EXPLAIN,
                        question = "How does competitive enzyme inhibition affect apparent Km and Vmax?",
                        answer = "Apparent Km increases (lower affinity), while Vmax remains unchanged at high substrate concentration.",
                        explanation = "Because a competitive inhibitor binds reversibly to the active site, excess substrate can outcompete it and still reach full Vmax.",
                        hint = "Can enough substrate overcome the inhibitor?",
                        options = emptyList(),
                        correctOptionIndex = -1
                    ),
                    mastered = true
                )
            )
        ),
        SampleDeckBundle(
            title = "Computer Architecture · Memory",
            subject = "CS",
            description = "CPU cache hierarchies, pipelining hazards, and virtual memory translation.",
            sourceType = DeckSourceType.MANUAL,
            accentIndex = 4,
            cards = listOf(
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "cs_1",
                        cardType = CardType.CONCEPT_EXPLAIN,
                        question = "Distinguish between temporal locality and spatial locality in CPU caches.",
                        answer = "Temporal locality means recently accessed data will likely be reused soon; spatial locality means nearby memory addresses will likely be accessed soon.",
                        explanation = "Caches exploit temporal locality by keeping hot lines resident and spatial locality by fetching multi-byte cache blocks (e.g., 64B).",
                        hint = "Time vs. neighboring addresses.",
                        options = emptyList(),
                        correctOptionIndex = -1
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "cs_2",
                        cardType = CardType.MULTIPLE_CHOICE,
                        question = "What hardware structure accelerates virtual-to-physical page address translation?",
                        answer = "Translation Lookaside Buffer (TLB)",
                        explanation = "The TLB is a small, fast associative cache inside the MMU storing recent page-table entries to avoid extra DRAM lookups.",
                        hint = "A specialized MMU cache.",
                        options = listOf(
                            "Branch Target Buffer (BTB)",
                            "Translation Lookaside Buffer (TLB)",
                            "Reorder Buffer (ROB)",
                            "Write-Back Victim Buffer"
                        ),
                        correctOptionIndex = 1
                    )
                ),
                SampleCardSeed(
                    draft = GeneratedCardDraft(
                        localId = "cs_3",
                        cardType = CardType.FILL_IN_BLANK,
                        question = "According to Amdahl's Law, overall speedup is fundamentally capped by the ___ fraction of a program.",
                        answer = "serial",
                        explanation = "Speedup = 1 / ((1 − P) + P/N). Even with infinite cores N → ∞, maximum speedup cannot exceed 1 / (1 − P).",
                        hint = "The non-parallelizable portion.",
                        options = emptyList(),
                        correctOptionIndex = -1
                    ),
                    mastered = true
                )
            )
        )
    )
}
