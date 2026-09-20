package com.stratum.legacy.domain

/**
 * CHAPTER 08: DOMAIN MODEL - ANCIENT IGBO HISTORY & ART CODEX
 *
 * Cultural encyclopedia entities driving lore, UI, mechanics, and art references.
 */
data class IgboCodexEntry(
    val id: String,
    val title: String,
    val igboTitle: String,
    val era: String,
    val category: CodexCategory,
    val summary: String,
    val detailedHistory: String,
    val gameLoopMechanicTieIn: String,
    val visualSymbol: String
)

enum class CodexCategory(val label: String) {
    METALLURGY_ART("Metallurgy & Fine Art"),
    SCRIPT_LANGUAGE("Nsibidi Script & Symbols"),
    GOVERNANCE_KINGDOMS("Kingdoms & Civilizations"),
    COSMOLOGY_DEITIES("Cosmology & Deities"),
    REGALIA_WEAPONS("Regalia & Armaments")
}

object IgboHistoryCodexArchive {

    val entries: List<IgboCodexEntry> = listOf(
        IgboCodexEntry(
            id = "codex_igbo_ukwu",
            title = "Igbo-Ukwu Bronze Masterpieces",
            igboTitle = "Ọkà Ọla Igbo-Ukwu",
            era = "9th Century AD (c. 850 AD)",
            category = CodexCategory.METALLURGY_ART,
            summary = "The pinnacle of ancient African bronze casting, predating European Renaissance casting techniques by centuries.",
            detailedHistory = """
                Discovered in 1938 at Igbo-Ukwu in Anambra State by Isaiah Anozie, the excavations at Igbo-Ukwu revealed a sophisticated metallurgical civilization active over a millennium ago.
                
                The site yielded over 700 high-leaded bronze artifacts, thousands of glass beads, and ritual regalia. The crowning achievement is the 'Roped Bronze Pot' (an exquisite vessel cast in one piece enclosed in a delicate openwork cage of bronze ropes) and intricate altar stands depicting horned chameleons, beetles, and coiled snakes.
                
                Unlike Benin or Ife bronzes which are primarily copper-zinc brass, Igbo-Ukwu used a distinct alloy of copper, tin, and high lead, utilizing the lost-wax (cire perdue) technique with unparalleled geometric precision and concentric spiral filigree.
            """.trimIndent(),
            gameLoopMechanicTieIn = "Grants the 'Roped Bronze Coils' weapon affix, wrapping attacking enemies in bronze filament for damage and stun.",
            visualSymbol = "🏺"
        ),
        IgboCodexEntry(
            id = "codex_nsibidi",
            title = "Nsibidi Ideographic Writing",
            igboTitle = "Edemede Nsibidi",
            era = "Ancient (c. 400 AD – Present)",
            category = CodexCategory.SCRIPT_LANGUAGE,
            summary = "An ancient indigenous system of pictograms and ideograms originating among the Ekpe and Cross River Igbo societies.",
            detailedHistory = """
                Nsibidi is one of Africa's oldest recorded writing systems, independently developed in southeastern Nigeria. 
                
                It comprises hundreds of distinct characters that convey complex philosophies, historical treaties, judicial verdicts, marital alliances, and esoteric spiritual mysteries. It was traditionally inscribed on pottery, carved on bronze ceremonial blades, stamped on cloth (Ukara), and painted on ancestral shrine walls.
                
                Initiates of the Ekpe society learned both the public script and the sacred secret hieroglyphs used to record esoteric councils and diplomatic covenants.
            """.trimIndent(),
            gameLoopMechanicTieIn = "Powers the Nsibidi Runes in the weapon forge, giving socketable glyphs that trigger elemental explosions and crits.",
            visualSymbol = "☩"
        ),
        IgboCodexEntry(
            id = "codex_uli_art",
            title = "Uli Curvilinear Geometric Art",
            igboTitle = "Nka Uli",
            era = "Pre-colonial – Modern Revival",
            category = CodexCategory.METALLURGY_ART,
            summary = "Traditional curvilinear, asymmetrical design painted on earthen compound walls and human skin.",
            detailedHistory = """
                Uli was predominantly practiced by Igbo women artists who transformed the walls of family compounds and communal shrines (Mbari) into breathtaking canvases of flowing, abstract geometry.
                
                Extracted from the pods of Rothmannia plants, Uli dye was paired with four sacred earth pigments: Nzu (white kaolin chalk), Edo (vibrant yellow clay), Ufie (red camwood), and Anwụ (lampblack charcoal).
                
                Uli prioritizes negative space, deliberate asymmetry, and motifs drawn from nature: crescent moons (Ọnwa), python markings (Eke), leopard claws (Mbọ Agụ), and coiled tendrils.
            """.trimIndent(),
            gameLoopMechanicTieIn = "Inspires the clean, elegant UI aesthetics and Uli-Inscribed Bows with curving trajectory arrows.",
            visualSymbol = "✦"
        ),
        IgboCodexEntry(
            id = "codex_nri_kingdom",
            title = "The Kingdom of Nri & Divine Kingship",
            igboTitle = "Ọchịchị Eze Nri",
            era = "c. 900 AD – 1911 AD",
            category = CodexCategory.GOVERNANCE_KINGDOMS,
            summary = "A divine, pacifist spiritual commonwealth that exerted moral and ritual hegemony across southeastern Nigeria without standing armies.",
            detailedHistory = """
                Founded by the semi-divine figure Eri, the Kingdom of Nri was centered in the heartland of Igboland. It is one of the most remarkable civilizations in world history for expanding its influence entirely through religious, ecological, and moral authority rather than military conquest.
                
                The ruler, Eze Nri, was considered a sacred monarch who had undergone a symbolic death and resurrection ceremony. Eze Nri's priests traveled throughout Igboland to consecrate peace treaties, purify lands from moral abominations (Alu), ordain titleholders, and distribute the sacred Ofo staff of justice.
            """.trimIndent(),
            gameLoopMechanicTieIn = "Inspires the 'Eze Nri Diviner' P&P RPG class and the peace aura mechanic that reduces enemy aggression.",
            visualSymbol = "👑"
        ),
        IgboCodexEntry(
            id = "codex_amadioha",
            title = "Amadioha: Deity of Thunder & Cosmic Justice",
            igboTitle = "Amadịọha / Kamalu",
            era = "Immortal Cosmology",
            category = CodexCategory.COSMOLOGY_DEITIES,
            summary = "The divine force of lightning, celestial thunder, solar heat, and uncompromising moral retribution.",
            detailedHistory = """
                In Igbo cosmology, Amadioha represents the wrath of the heavens and the swift sword of moral righteousness. He is associated with the color white, the white ram, and noon-day lightning strikes.
                
                Those accused of grave injustice or theft were often brought before an Amadioha shrine; a sudden thunderclap was interpreted as cosmic judgment. Amadioha is closely allied with Anyanwu (the sun) and Ala (the earth).
            """.trimIndent(),
            gameLoopMechanicTieIn = "Directly inspires the 'Egbe Amadioha' primary skill in the ARPG combat loop, striking foes with electric chain bolts.",
            visualSymbol = "⚡"
        ),
        IgboCodexEntry(
            id = "codex_ikenga",
            title = "Ikenga: The Horned Spirit of Enterprise",
            igboTitle = "Ikenga Ike",
            era = "Ancestral Igbo Tradition",
            category = CodexCategory.REGALIA_WEAPONS,
            summary = "A carved consecrated effigy depicting a seated warrior with majestic ram horns, holding a blade in the right hand.",
            detailedHistory = """
                The Ikenga embodies personal agency, moral fortitude, entrepreneurial drive, and physical conquest. The prominent ram horns symbolize determination: a ram fights with head and horns first, never retreating once engaged.
                
                Every accomplished Igbo man and woman consecrated an Ikenga to celebrate achievements earned through honest labor and the strength of the right arm (Aka Ikenga).
            """.trimIndent(),
            gameLoopMechanicTieIn = "Inspires the 'Ikenga Horned Cleaver' heavy weapon and the 'Horn of Conquest' life leech proc mechanic.",
            visualSymbol = "♈"
        ),
        IgboCodexEntry(
            id = "codex_ofo_scepter",
            title = "The Ọfọ: Scepter of Moral Integrity",
            igboTitle = "Ọfọ Ndu na Eziokwu",
            era = "Ancestral Sacred Regalia",
            category = CodexCategory.REGALIA_WEAPONS,
            summary = "A sacred wooden or bronze staff harvested from the Detarium senegalense tree, embodying truth and ancestral mandate.",
            detailedHistory = """
                The Ofo is the highest sacred symbol of truth, authority, and justice in Igbo society. It symbolizes the Igbo maxim 'Egbe belu, ugo belu' (Let the eagle perch, and let the hawk perch—whoever denies the other, let its wings break).
                
                Whenever an elder, priest, or titleholder strikes the Ofo on the ground, the ancestors bear witness that only absolute truth has been spoken.
            """.trimIndent(),
            gameLoopMechanicTieIn = "Base weapon type for divine spellcasters in the game engine, delivering holy thunder damage.",
            visualSymbol = "🪄"
        )
    )
}
