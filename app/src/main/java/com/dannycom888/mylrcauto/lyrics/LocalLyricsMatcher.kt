package com.dannycom888.mylrcauto.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * Pure filename matching for local `.lrc` files.
 *
 * Ported from the local-only Melisma build's `LocalFolderMatcher` (same
 * rules, adapted to this app's file types): file names are matched against
 * the playing track with two preference tiers:
 * 1. `"Artist - Title.lrc"` (the artist part must be one of the track's artists).
 * 2. `"Title.lrc"`.
 *
 * Matching is done on a normalized form: full-width characters are folded to
 * half-width, Traditional characters are folded to Simplified and zero-width
 * characters are stripped (v1.0.24, see [normalize]), the text is lower-cased,
 * and every character that is not a letter or digit is dropped, so
 * `"周杰伦 - 晴天.lrc"`, `"周杰伦-晴天.lrc"` and
 * `"周杰伦 晴天.lrc"` all match the same track. Parenthesised or bracketed
 * version suffixes in the file name (for example `"晴天 (Live).lrc"`) are also
 * tried stripped, so they still match the plain title.
 *
 * Multi-artist credits are split on both sides: a file named
 * `"庄心妍_祁隆 - 一万个舍不得.lrc"` matches a track whose only reported
 * artist is `"庄心妍"` — and vice versa, a track credited to
 * `"庄心妍_祁隆"` matches `"庄心妍 - 一万个舍不得.lrc"`. The artist part is
 * split on `_`, `、`, `&`, `,`, `+`, `/`, `feat.`/`ft.`, and the Chinese
 * `与`/`和`; the title part must still equal the track title after
 * normalization. The key tiers stay strictly ordered — a file that names
 * the track's artist always wins — but since v1.0.23 the title-part tier
 * is a fallback for ANY empty key ranking, not only for artist-less
 * tracks: an exact title-part match (never a substring) is accepted when
 * nothing matched on the keys, so one wrong artist token from the player
 * cannot zero the whole lookup.
 *
 * This object is pure Kotlin with no Android dependency so the rules are
 * directly unit-testable on the JVM.
 *
 * Since v1.0.24, [normalize] also folds Traditional Chinese characters
 * to Simplified (see [TRADITIONAL_TO_SIMPLIFIED_PAIRS]) and strips
 * zero-width characters: the v1.0.23 device trace proved the received
 * title and the stored file names can differ at codepoint level
 * (Simplified tag vs Traditional file name, or vice versa) while
 * looking identical — every normalized comparison now happens in
 * Simplified space on BOTH sides, so script variants of the same
 * title/artist compare equal. The folding lives inside [normalize]
 * itself, so every existing call site (index keys, track keys, file
 * stems, the title guard) inherits it without changing any other
 * matching semantics.
 *
 * Since v1.0.25 both detailed lookups end in a conservative FUZZY
 * phase, reached only when every exact phase found nothing: a
 * candidate whose normalized title part has the SAME LENGTH as the
 * track's and sits within a small Levenshtein distance (1, or 2 for
 * titles of five characters or more — the bound was widened from
 * "longer than six" in v1.0.26) can still win — see
 * [fuzzyWinner]. The v1.0.24 device trace proved the need: the
 * playing track's tag title itself carried a one-character
 * homophone typo （优 for 犹）, so the correctly named files could
 * never compare equal. Same-length only — an insertion or deletion
 * is never accepted, which keeps the 爱/爱你 anti-substring
 * protection intact — an artist gate when both sides name an
 * artist, and only a unique minimum-distance winner is accepted.
 *
 * Since v1.0.26, [normalize] FIRST runs Unicode NFKC over its
 * input: compatibility ideographs (the U+F900–U+FAFF block and
 * friends) fold to their unified forms, which the curated T→S
 * table cannot express (those codepoints are neither Traditional
 * nor Simplified — they are duplicate encodings of the same
 * glyph). The 2026-11-13 second trace proved the need: the tag
 * title 因为爱所以爱 was codepoint-PERFECT, yet every phase
 * missed files displayed as "谢霆锋 - 因为爱所以爱" — the
 * divergence sat inside the stored file names themselves.
 */
object LocalLyricsMatcher {

    /** One indexed `.lrc` file with its precomputed match keys. */
    data class IndexedLrcFile(
        val file: LocalLrcFile,
        val keys: Set<String>,
    )

    /** One indexed audio file with its precomputed normalized stem. */
    data class IndexedAudioFile(
        val file: LocalAudioFile,
        val normalizedStem: String,
    )

    /**
     * Curated Traditional → Simplified character pairs, written as
     * one flat string of (traditional, simplified) character pairs
     * and read two characters at a time into
     * [TRADITIONAL_TO_SIMPLIFIED].
     *
     * Coverage is deliberately a COMMON-CHARACTER subset (a few
     * hundred pairs: everyday vocabulary plus characters common in
     * Chinese song titles and artist names), NOT a complete
     * Traditional→Simplified conversion table: a character outside
     * the table folds to itself, so an unlisted Traditional variant
     * in a file name still misses a Simplified tag (and the Settings
     * diagnostics now show the codepoints and the closest stored
     * names when that happens). One-to-one pairs only, except for a
     * few many-to-one folds that are unambiguous for matching
     * （發/髮→发， 乾 is intentionally NOT included — it is also a
     * surname reading gān). Identity pairs are omitted.
     */
    private val TRADITIONAL_TO_SIMPLIFIED_PAIRS: String =
        "萬万與与專专業业東东丟丢兩两嚴严個个豐丰臨临為为麗丽舉举麼么義义烏乌樂乐喬乔" +
        "習习鄉乡書书買买亂乱亞亚產产親亲億亿僅仅僕仆償偿優优兒儿兌兑內内關关興兴農农" +
        "準准凱凯刪删劉刘則则剛刚創创劃划劇剧勵励動动務务勝胜勞劳勢势勻匀區区醫医華华" +
        "協协單单賣卖盧卢衛卫廠厂卻却歷历壓压厭厌參参雙双發发髮发號号嘆叹國国圍围園园" +
        "圖图團团壞坏塊块堅坚壇坛壘垒墳坟墜坠牆墙壯壮聲声處处備备夠够夢梦頭头奪夺奮奋" +
        "妝妆婦妇媽妈孫孙學学寧宁寶宝實实寵宠審审寫写寬宽對对尋寻導导屆届屬属歲岁島岛" +
        "嶺岭幣币師师帳帐帶带幫帮廣广廟庙廢废開开閒闲閑闲間间閱阅閣阁陽阳陰阴陣阵陸陆" +
        "陳陈隊队階阶際际隨随險险隱隐雖虽雜杂雞鸡離离難难雲云電电霧雾靈灵靜静頂顶順顺" +
        "須须頌颂預预領领頰颊題题額额顏颜願愿顛颠類类風风飛飞飯饭飲饮餓饿館馆體体鬥斗" +
        "鬱郁鮮鲜鳥鸟鳴鸣龍龙龜龟來来們们這这時时還还過过進进遠远邊边點点後后裡里長长" +
        "門门問问見见視视觀观話话說说誰谁語语請请講讲謝谢謊谎貝贝負负財财貢贡貧贫貨货" +
        "販贩貪贪貴贵貸贷費费資资賞赏賜赐質质賬账購购趕赶趙赵趨趋跡迹踐践車车軍军軟软" +
        "輕轻輝辉輪轮輯辑輸输轉转轟轰辭辞邁迈遷迁選选遺遗鄧邓鄭郑釋释鐘钟鍾钟鋼钢錢钱" +
        "錦锦鏡镜鐵铁鑄铸鑰钥鑽钻銀银銅铜銳锐銷销鋪铺錄录錫锡鍵键鍛锻鎮镇閃闪閉闭閏闰" +
        "闊阔闖闯頁页項项顧顾顯显飾饰餘余驅驱驗验驚惊驛驿騎骑騰腾騷骚騙骗魚鱼魯鲁鴨鸭" +
        "鴻鸿鵝鹅鶯莺鷹鹰鹽盐麥麦黃黄黨党齊齐齒齿龔龚懷怀戀恋愛爱淚泪聽听肅肃聯联聰聪" +
        "職职臉脸舊旧艱艰艷艳節节範范簡简簽签籃篮筆笔築筑糾纠紀纪約约紅红級级純纯納纳" +
        "紐纽紋纹紛纷紡纺紮扎紳绅細细組组終终絆绊絃弦結结絕绝絞绞絡络給给絢绚統统絲丝" +
        "經经綢绸維维綴缀綵彩綸伦綺绮綻绽綿绵緒绪緘缄緙缂線线緝缉緞缎締缔練练緯纬緻致" +
        "緹缇緲缈縈萦縉缙縊缢縐绉縑缣縕缊縛缚縟缛縞缟縣县縫缝縮缩縱纵縴纤縵缦縷缕縹缥" +
        "總总績绩繃绷繅缫繆缪織织繕缮繚缭繞绕繡绣繢缋繩绳繪绘繭茧繹绎繼继繽缤續续" +
        "纏缠纓缨纘缵纖纤纜缆編编緩缓緊紧緬缅變变讓让護护讀读識识譯译證证評评詞词試试" +
        "詩诗誠诚認认誤误誘诱誦诵課课調调談谈論论諮咨諺谚謀谋謎谜謠谣謹谨謬谬諾诺諸诸" +
        "現现場场規规覺觉覽览訂订計计誕诞樹树葉叶楓枫櫻樱蘋苹檸柠嗎吗猶犹倫伦張张譚谭" +
        "詠咏榮荣楊杨許许蘇苏吳吴慶庆恆恒羅罗嶽岳賢贤曉晓憶忆蓮莲韓韩滿满駒驹貫贯裝装" +
        "週周著着歡欢"

    /** The folding table built from [TRADITIONAL_TO_SIMPLIFIED_PAIRS]. */
    private val TRADITIONAL_TO_SIMPLIFIED: Map<Char, Char> = buildMap {
        var i = 0
        while (i + 1 < TRADITIONAL_TO_SIMPLIFIED_PAIRS.length) {
            put(TRADITIONAL_TO_SIMPLIFIED_PAIRS[i], TRADITIONAL_TO_SIMPLIFIED_PAIRS[i + 1])
            i += 2
        }
    }

    /** How many Traditional → Simplified pairs the folding table carries. */
    fun traditionalFoldPairCount(): Int = TRADITIONAL_TO_SIMPLIFIED.size

    /** Folds one character Traditional → Simplified when the table knows it; identity otherwise. */
    fun foldTraditional(char: Char): Char = TRADITIONAL_TO_SIMPLIFIED[char] ?: char

    /**
     * Zero-width characters stripped by [normalize]: U+200B ZERO
     * WIDTH SPACE, U+200C ZERO WIDTH NON-JOINER, U+200D ZERO WIDTH
     * JOINER and U+FEFF (the byte-order mark / zero-width no-break
     * space). A tag editor or a copy-paste can plant any of them
     * inside a title; they are invisible in every UI, so a title
     * carrying one can never equal the same title without it.
     */
    private val ZERO_WIDTH_CHARS = setOf('\u200B', '\u200C', '\u200D', '\uFEFF')

    /**
     * Folds full-width ASCII and the full-width space to half-width. This is
     * the first step of [normalize] and is also used before splitting file
     * names, so full-width separators (for example `－` or `＆`) behave like
     * their half-width forms.
     */
    fun foldFullWidth(value: String): String {
        val folded = StringBuilder(value.length)
        for (char in value) {
            folded.append(
                when {
                    char == '\u3000' -> ' '
                    char in '\uFF01'..'\uFF5E' -> (char.code - 0xFEE0).toChar()
                    else -> char
                },
            )
        }
        return folded.toString()
    }

    /**
     * Normalizes one string for comparison. The input FIRST runs
     * through Unicode compatibility decomposition (NFKC, v1.0.26):
     * CJK compatibility ideographs (U+F900–U+FAFF) fold to their
     * unified forms and full-width forms to half-width. Then
     * full-width characters are folded to half-width (kept after
     * NFKC as belt and braces), Traditional Chinese characters are
     * folded to Simplified ([foldTraditional]), zero-width
     * characters are stripped, the text is lower-cased, and
     * everything that is not a letter or digit is removed.
     * All matching in this object compares normalized forms, so
     * folding here — and only here — makes every comparison
     * script-insensitive on both sides (index keys and query
     * inputs alike).
     */
    fun normalize(value: String): String {
        val compatible = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val folded = StringBuilder(compatible.length)
        for (rawChar in foldFullWidth(compatible)) {
            if (rawChar in ZERO_WIDTH_CHARS) continue
            val char = foldTraditional(rawChar)
            if (char.isLetterOrDigit()) {
                folded.append(char.lowercaseChar())
            }
        }
        return folded.toString()
    }

    /**
     * Separators between individual artists in one credit. `feat.`/`ft.` only
     * split when they are not part of another word, so an artist such as
     * "Taylor Swift" is not cut at the "ft" in "Swift".
     */
    private val ARTIST_SEPARATORS = Regex(
        """(?i)(?<![A-Za-z])(?:feat\.?|ft\.?)(?![A-Za-z])|[_、&,，+/与和]""",
    )

    /**
     * Splits one artist credit (a file-name artist part or a track artist)
     * into individual artists. Empty segments are dropped.
     */
    fun splitArtists(value: String): List<String> {
        return foldFullWidth(value)
            .split(ARTIST_SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() && normalize(it).isNotEmpty() }
    }

    /**
     * Splits a file-name stem into its artist part and title part on the
     * first `" - "` (a bare `"-"` without spaces is also accepted). Returns
     * null when the stem has no usable separator. The spaced separator is
     * tried first so a hyphenated artist name such as `"TF-Boys"` is not
     * cut apart.
     */
    fun splitArtistTitle(stem: String): Pair<String, String>? {
        val folded = foldFullWidth(stem)
        val spaced = Regex("\\s+-\\s+").find(folded)
        if (spaced != null) {
            val artist = folded.substring(0, spaced.range.first).trim()
            val title = folded.substring(spaced.range.last + 1).trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) return artist to title
            return null
        }
        val bare = folded.indexOf('-')
        if (bare >= 0) {
            val artist = folded.substring(0, bare).trim()
            val title = folded.substring(bare + 1).trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) return artist to title
        }
        return null
    }

    /** Removes `(...)` and `[...]` segments, used for version suffixes such as `(Live)`. */
    fun stripBracketed(value: String): String {
        return value
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
    }

    /**
     * Match keys for one `.lrc` file name, without the extension.
     *
     * Besides the normalized whole stem (and its bracket-stripped form), a
     * file with an artist part contributes one `"Artist - Title"` key per
     * individual artist in the credit, so `"庄心妍_祁隆 - 一万个舍不得.lrc"`
     * also matches a track credited only to `"庄心妍"`. A file with no
     * artist part contributes only its title keys.
     */
    fun fileKeys(fileName: String): Set<String> {
        val baseName = fileName.removeSuffix(".lrc").removeSuffix(".LRC")
        val keys = mutableSetOf(normalize(baseName))
        val stripped = normalize(stripBracketed(baseName))
        if (stripped.isNotEmpty()) keys += stripped
        val (artistPart, titlePart) = splitArtistTitle(baseName) ?: return keys
        val titleVariants = linkedSetOf(titlePart, stripBracketed(titlePart))
            .filter { normalize(it).isNotEmpty() }
        for (segment in splitArtists(artistPart)) {
            for (variant in titleVariants) {
                val key = normalize("$segment - $variant")
                if (key.isNotEmpty()) keys += key
            }
        }
        return keys
    }

    /**
     * Ordered match keys for a track: `"Artist - Title"` for each artist (the
     * raw credit first, then each individual artist in a multi-artist credit)
     * first, then the bare title. Earlier keys are preferred over later ones.
     */
    fun trackKeys(title: String, artists: List<String>): List<String> {
        val keys = linkedSetOf<String>()
        for (artist in artists) {
            keys += artistTitleKeys(artist, title)
        }
        keys += normalize(title)
        return keys.toList()
    }

    /**
     * `"Artist - Title"` keys for one artist: the raw credit first, then each
     * individual artist in the credit.
     */
    fun artistTitleKeys(artist: String, title: String): List<String> {
        val keys = linkedSetOf<String>()
        keys += normalize("$artist - $title")
        for (segment in splitArtists(artist)) {
            keys += normalize("$segment - $title")
        }
        return keys.toList()
    }

    /**
     * Number of artist-tier keys in [trackKeys]: every key except the trailing
     * bare-title key. Used to tell whether a match is an artist-title match.
     */
    fun artistKeyCount(title: String, artists: List<String>): Int {
        val seen = linkedSetOf<String>()
        var count = 0
        for (artist in artists) {
            for (key in artistTitleKeys(artist, title)) {
                if (seen.add(key)) count++
            }
        }
        return count
    }

    /** Normalized stem of a file name: the name without its extension, normalized. */
    fun fileStem(fileName: String): String {
        return normalize(fileName.substringBeforeLast('.', fileName))
    }

    /**
     * True when the `.lrc` file name has exactly the audio file's stem — the
     * pairing rule Poweramp uses. Compares file names only, so it is usable
     * from either side of the match.
     */
    fun sameStemLrc(audioFileName: String, lrcFileName: String): Boolean {
        return fileStem(audioFileName) == fileStem(lrcFileName)
    }

    /**
     * A [findAudioFileDetailed] result: the file that was picked and
     * which relaxation produced it, when one did.
     */
    data class AudioFileMatch(
        val entry: IndexedAudioFile,
        /** True when the title+artist pass found nothing and the title-only retry picked this file. */
        val titleOnlyRetry: Boolean,
        /** True when every exact pass found nothing and the v1.0.25 fuzzy phase picked this file. */
        val fuzzyMatch: Boolean = false,
    )

    /**
     * Finds the audio file that is playing: its normalized stem must contain
     * the normalized track title and at least one normalized artist segment
     * (an individual artist from a multi-artist credit). When the track
     * reports no artist at all, the title alone has to do — the pairing rule
     * still applies through the same-stem `.lrc` lookup, and the residue
     * tie-break still prefers the least extra text. When several files
     * qualify, the one with the least extra text besides the title and the
     * matched artist wins; ties keep index order.
     */
    fun findAudioFile(
        title: String,
        artists: List<String>,
        audioFiles: List<IndexedAudioFile>,
    ): IndexedAudioFile? = findAudioFileDetailed(title, artists, audioFiles)?.entry

    /**
     * [findAudioFile] with the pass that produced the hit recorded, for
     * the lookup trace.
     *
     * v1.0.23 relaxation: when the track reports artists but NO stem
     * contains one of them alongside the title, the search retries on
     * the title alone (residue-ranked the same way). The artist pass
     * stays strictly first, so a file that names the artist always
     * beats a title-only file; the retry only fires when the artist
     * pass came back empty — the shape the 2026-11-12 device traces
     * showed (audio found: NO for files whose stems carry the title).
     * The pick stays safe for the step that consumes it: the same-stem
     * `.lrc` still has to pass the exact title check
     * ([lrcTitleMatches]) before anything is shown.
     *
     * v1.0.25: when the retry also finds nothing, the fuzzy last
     * resort runs ([fuzzyWinner] over the audio entries' title
     * parts): a same-length title part within the distance bound
     * can still pick the file — the tag-typo shape, where the
     * playing file is correctly named but the player's title is
     * one character off, so no containment check can ever see it.
     * A fuzzy winner is a normal audio hit from here on: the
     * same-stem `.lrc` proceeds through the same exact title check
     * as any other hit.
     */
    fun findAudioFileDetailed(
        title: String,
        artists: List<String>,
        audioFiles: List<IndexedAudioFile>,
    ): AudioFileMatch? {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return null
        val artistSegments = artists
            .flatMap { splitArtists(it) }
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val primary = bestAudioMatch(
            normalizedTitle = normalizedTitle,
            artistSegments = artistSegments,
            audioFiles = audioFiles,
            requireArtist = artistSegments.isNotEmpty(),
        )
        if (primary != null) return AudioFileMatch(primary, titleOnlyRetry = false)
        if (artistSegments.isNotEmpty()) {
            val retry = bestAudioMatch(
                normalizedTitle = normalizedTitle,
                artistSegments = artistSegments,
                audioFiles = audioFiles,
                requireArtist = false,
            )
            if (retry != null) return AudioFileMatch(retry, titleOnlyRetry = true)
        }
        val fuzzy = fuzzyWinner(
            queryTitle = title,
            queryArtistSegments = artistSegments,
            entries = audioFiles,
        ) { entry -> fileNameParts(entry.file.fileName, lrc = false) }
        return fuzzy?.let {
            AudioFileMatch(it, titleOnlyRetry = false, fuzzyMatch = true)
        }
    }

    /**
     * The residue-ranked containment search behind [findAudioFile]:
     * stems containing the title (and, when [requireArtist], one of
     * the artist segments), least extra text first.
     */
    private fun bestAudioMatch(
        normalizedTitle: String,
        artistSegments: Set<String>,
        audioFiles: List<IndexedAudioFile>,
        requireArtist: Boolean,
    ): IndexedAudioFile? {
        return audioFiles
            .mapNotNull { entry ->
                val stem = entry.normalizedStem
                if (!stem.contains(normalizedTitle)) return@mapNotNull null
                val segment = if (!requireArtist) "" else
                    artistSegments.firstOrNull { stem.contains(it) }
                        ?: return@mapNotNull null
                val residue = (stem.length - normalizedTitle.length - segment.length).coerceAtLeast(0)
                residue to entry
            }
            .minByOrNull { it.first }
            ?.second
    }

    /**
     * Plain Levenshtein edit distance over UTF-16 characters
     * (substitution/insertion/deletion each cost 1). The inputs
     * here are already normalized and short (a title part), so a
     * plain dynamic program is fast enough — the fuzzy phase only
     * runs when every exact phase has already failed, and the
     * caller only calls it on equal-length pairs (see
     * [fuzzyWinner]), where insertions and deletions can never
     * contribute anyway.
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            previous = current
        }
        return previous[b.length]
    }

    /**
     * How far apart two equal-length title parts may be and still
     * count as the same song with a typo in it: one wrong
     * character for titles up to four characters, two for titles
     * of five or more — a second typo in a very short title is
     * more likely a different song than a misspelling, and a
     * longer title has room for two. (v1.0.25 set the two-typo
     * bound at "longer than six"; v1.0.26 widened it to five or
     * more after the 因为爱所以爱 device trace — a six-character
     * title whose stored name differs in two positions.)
     */
    fun fuzzyMaxDistance(titleLength: Int): Int = if (titleLength >= 5) 2 else 1

    /**
     * The artist and title parts of one indexed file name, in
     * normalized form. The title part keeps both the raw and the
     * bracket-stripped variants (a `(Live)` suffix still belongs
     * to the same title), and the artist part is the individual
     * segments of its credit.
     */
    private data class FileNameParts(
        /** Individual normalized artists of the name's artist part; empty when there is none. */
        val artistSegments: Set<String>,
        /** The name's normalized title part, plus the bracket-stripped form when it differs. */
        val titleVariants: Set<String>,
    )

    /** Splits one file name into its artist and title parts (see [FileNameParts]). */
    private fun fileNameParts(fileName: String, lrc: Boolean): FileNameParts {
        val stem = if (lrc) {
            fileName.removeSuffix(".lrc").removeSuffix(".LRC")
        } else {
            fileName.substringBeforeLast('.', fileName)
        }
        val split = splitArtistTitle(stem)
        val artistPart = split?.first
        val titlePart = split?.second ?: stem
        val titleVariants = linkedSetOf<String>()
        for (variant in listOf(titlePart, stripBracketed(titlePart))) {
            val normalized = normalize(variant)
            if (normalized.isNotEmpty()) titleVariants += normalized
        }
        val segments = artistPart?.let {
            splitArtists(it).map { segment -> normalize(segment) }
                .filter { segment -> segment.isNotEmpty() }.toSet()
        } ?: emptySet()
        return FileNameParts(artistSegments = segments, titleVariants = titleVariants)
    }

    /**
     * The conservative fuzzy last resort (v1.0.25), shared by the
     * audio search ([findAudioFileDetailed]) and the name match
     * ([matchDetailed]) — it only ever runs after every exact phase
     * of its caller has come back empty.
     *
     * A candidate qualifies only when one of its normalized title
     * variants has the SAME LENGTH as the query title and sits
     * within [fuzzyMaxDistance] of it (distance 0 is excluded —
     * an exact title was the earlier phases' job, and letting an
     * exact pair through would mislabel an exact hit as fuzzy).
     * Same length is the hard line: a length difference is an
     * insertion or deletion, so a shorter title can never fuzzy
     * into a longer one — the 爱/爱你 protection holds here by
     * construction, and is pinned by tests in both directions.
     *
     * The artist gate: when the query names artists, a candidate
     * whose name carries an artist part must share at least one
     * normalized artist segment with the query — otherwise it is
     * rejected outright (a wrong-artist near-title is a different
     * song). A candidate with no artist part is allowed but ranks
     * below artist-matching ones: artist-matching candidates are
     * considered first, and the winner is chosen inside that
     * tier. When the query names no artist at all, every
     * candidate is in the same (only) tier.
     *
     * Only a UNIQUE minimum-distance winner is accepted: when two
     * or more candidates in the deciding tier tie at the minimum
     * distance, the lookup matches nothing — guessing between two
     * near-miss songs is worse than showing none.
     *
     * @param entries the candidates to consider, in index order.
     * @param partsOf how to get one candidate's [FileNameParts].
     * @return the winning entry, or null when nothing qualifies.
     */
    private fun <T> fuzzyWinner(
        queryTitle: String,
        queryArtistSegments: Set<String>,
        entries: List<T>,
        partsOf: (T) -> FileNameParts,
    ): T? {
        val queryVariants = linkedSetOf(
            normalize(queryTitle),
            normalize(stripBracketed(queryTitle)),
        ).filter { it.isNotEmpty() }
        if (queryVariants.isEmpty()) return null
        // (entry, distance, artistMatched)
        val scored = entries.mapNotNull { entry ->
            val parts = partsOf(entry)
            if (parts.titleVariants.isEmpty()) return@mapNotNull null
            val artistMatched = when {
                queryArtistSegments.isEmpty() -> false
                parts.artistSegments.isEmpty() -> false
                parts.artistSegments.any { it in queryArtistSegments } -> true
                else -> return@mapNotNull null
            }
            val distance = queryVariants
                .flatMap { query ->
                    parts.titleVariants.mapNotNull { candidate ->
                        // Equal lengths only, ever: a length
                        // difference is an insertion or deletion,
                        // and accepting one would break the
                        // 爱/爱你 protection. The bound is the
                        // compared pair's own length's bound.
                        if (candidate.length != query.length) {
                            null
                        } else {
                            levenshtein(query, candidate)
                                .takeIf { it in 1..fuzzyMaxDistance(query.length) }
                        }
                    }
                }
                .minOrNull() ?: return@mapNotNull null
            Triple(entry, distance, artistMatched)
        }
        if (scored.isEmpty()) return null
        // Artist-matching candidates outrank the rest BEFORE the
        // distance comparison: the deciding tier is the highest
        // non-empty one, and the winner is the unique minimum
        // inside it.
        val tier = if (scored.any { it.third }) {
            scored.filter { it.third }
        } else {
            scored
        }
        val minimum = tier.minOf { it.second }
        val winners = tier.filter { it.second == minimum }
        return if (winners.size == 1) winners.single().first else null
    }

    /**
     * True when the `.lrc` file's title part is the track's title — the
     * guard for the audio-anchored lookup ([LocalLyricsProvider] step 1).
     * [findAudioFile] matches the *audio* file by substring, so it can
     * pick a longer file ("爱你.mp3") for a shorter title ("爱"); the
     * same-stem `.lrc` then has the wrong title too, and returning it
     * would show the wrong song's lyrics. This check compares the title
     * parts after normalization with bracketed suffixes stripped, so
     * "晴天 (Live).lrc" still matches "晴天" while "爱你.lrc" does not
     * match "爱". A file with an artist part contributes only its title
     * part; a file with no separator is compared whole.
     */
    fun lrcTitleMatches(lrcFileName: String, title: String): Boolean {
        val stem = lrcFileName.removeSuffix(".lrc").removeSuffix(".LRC")
        val titlePart = splitArtistTitle(stem)?.second ?: stem
        return normalize(stripBracketed(titlePart)) == normalize(stripBracketed(title))
    }

    /**
     * The `.lrc` file whose stem is exactly the audio file's stem — the
     * pairing rule Poweramp uses. Returns null when no indexed `.lrc` has
     * that stem.
     */
    fun sameStemLrc(
        audioFile: IndexedAudioFile,
        lrcIndex: List<IndexedLrcFile>,
    ): IndexedLrcFile? {
        return lrcIndex.firstOrNull { entry ->
            fileStem(entry.file.fileName) == audioFile.normalizedStem
        }
    }

    /**
     * A [matchDetailed] result: the matched files, and which
     * relaxation produced them when the key ranking did not.
     */
    data class NameMatchResult(
        val files: List<IndexedLrcFile>,
        /** True when the key ranking matched nothing and the title-only phase produced these files. */
        val titleOnlyFallback: Boolean,
        /** True when every exact phase matched nothing and the v1.0.25 fuzzy phase produced these files. */
        val fuzzyMatch: Boolean = false,
    )

    /**
     * Returns the indexed files that match the track, ordered by preference:
     * files matching an earlier track key come first; files matching the same
     * key keep their index order.
     *
     * When the key ranking matches nothing, a title-only phase runs:
     * files whose TITLE PART is exactly the track title (normalized,
     * bracketed suffixes stripped — exact equality, never a substring,
     * so "爱" still does not match "爱你.lrc"). Until v1.0.23 that
     * phase only ran when the track reported no artist at all, which
     * made the fallback all-or-nothing on the artist string: one
     * wrong or extra artist token from the player zeroed it even when
     * an exact-title file was indexed. It now runs whenever the key
     * ranking is empty — the ranking stays strictly first, so a file
     * that names the track's artist always beats a title-only file.
     *
     * Since v1.0.25, when the title-only phase also matches nothing,
     * the fuzzy last resort runs ([fuzzyWinner] over the `.lrc`
     * entries' title parts): a same-length title part one typo away
     * can still return its file — the shape the 2026-11-13 device
     * trace proved, where the TAG title itself was mistyped
     * （优 for 犹） and every exact phase was right to miss.
     */
    fun match(
        title: String,
        artists: List<String>,
        indexed: List<IndexedLrcFile>,
    ): List<IndexedLrcFile> = matchDetailed(title, artists, indexed).files

    /** [match] with the producing phase recorded, for the lookup trace. */
    fun matchDetailed(
        title: String,
        artists: List<String>,
        indexed: List<IndexedLrcFile>,
    ): NameMatchResult {
        val keys = trackKeys(title, artists)
        val ranked = indexed.mapNotNull { entry ->
            val bestKeyRank = keys.indexOfFirst { key -> key in entry.keys }
            if (bestKeyRank < 0) null else bestKeyRank to entry
        }.sortedBy { it.first }.map { it.second }
        if (ranked.isNotEmpty()) return NameMatchResult(ranked, titleOnlyFallback = false)
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return NameMatchResult(emptyList(), titleOnlyFallback = false)
        val byTitlePart = indexed.filter { entry -> titlePartMatches(entry, normalizedTitle) }
        if (byTitlePart.isNotEmpty()) {
            return NameMatchResult(byTitlePart, titleOnlyFallback = true)
        }
        val artistSegments = artists
            .flatMap { splitArtists(it) }
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val fuzzy = fuzzyWinner(
            queryTitle = title,
            queryArtistSegments = artistSegments,
            entries = indexed,
        ) { entry -> fileNameParts(entry.file.fileName, lrc = true) }
        return if (fuzzy != null) {
            NameMatchResult(listOf(fuzzy), titleOnlyFallback = false, fuzzyMatch = true)
        } else {
            NameMatchResult(emptyList(), titleOnlyFallback = false)
        }
    }

    /**
     * True when the file's title part is exactly [normalizedTitle]
     * (the caller passes the already-normalized track title), with
     * bracketed version suffixes stripped — the exact-equality check
     * behind the title-only phase of [match]. A file with no
     * artist/title separator never qualifies here: its whole stem is
     * its title, and the key ranking already tried that.
     */
    private fun titlePartMatches(entry: IndexedLrcFile, normalizedTitle: String): Boolean {
        val stem = entry.file.fileName.removeSuffix(".lrc").removeSuffix(".LRC")
        val (_, titlePart) = splitArtistTitle(stem) ?: return false
        return normalize(titlePart) == normalizedTitle ||
            normalize(stripBracketed(titlePart)) == normalizedTitle
    }

    /**
     * Picks the winner among matched files that were parsed successfully.
     *
     * Files matching an earlier track key win over files matching a later key
     * ("Artist - Title" beats title-only). Within the same key tier, when the
     * track duration is known, the file whose last lyric timestamp is closest
     * to the track duration wins; when the duration is unknown, the first file
     * in match order wins.
     *
     * @param parsed each entry is a matched file with its parsed last-line
     *   timestamp in milliseconds (null when the file has no timed lines).
     */
    fun pickWinner(
        title: String,
        artists: List<String>,
        durationMs: Long,
        parsed: List<Pair<IndexedLrcFile, Long?>>,
    ): IndexedLrcFile? {
        if (parsed.isEmpty()) return null
        val keys = trackKeys(title, artists)
        val bestRank = parsed.minOf { (file, _) -> keyRank(keys, file) }
        val tier = parsed.filter { (file, _) -> keyRank(keys, file) == bestRank }
        if (durationMs <= 0L) return tier.first().first
        return tier.minByOrNull { (_, lastLineMs) ->
            val last = lastLineMs ?: Long.MAX_VALUE
            if (last == Long.MAX_VALUE) Long.MAX_VALUE else kotlin.math.abs(last - durationMs)
        }?.first
    }

    /**
     * Where a file sits in the track's key order. A file that matches no key
     * — one that only reached the match through the no-artist title-part
     * fallback — ranks last, never first: without this, `-1` would beat a
     * genuine title-only match.
     */
    private fun keyRank(keys: List<String>, file: IndexedLrcFile): Int {
        val rank = keys.indexOfFirst { key -> key in file.keys }
        return if (rank < 0) Int.MAX_VALUE else rank
    }

    /**
     * Decodes `.lrc` bytes to text.
     *
     * Order: a UTF-16 byte-order mark first (FF FE → little-endian,
     * FE FF → big-endian) — a Windows editor's "Unicode" save is the
     * common source, and players that sniff the mark (Poweramp) show
     * such a file's lyrics fine. Then strict UTF-8, then GBK (common
     * for Chinese `.lrc` files saved on Windows). A leading UTF-8
     * byte-order mark is removed.
     *
     * The mark matters because a UTF-16 file run through the UTF-8 /
     * GBK path does not fail — it decodes into NUL-interleaved
     * garbage (every ASCII byte gains a NUL partner; NUL is a valid
     * byte in both decodings, so no exception ever routes to a
     * fallback), no time tag ever matches, and the file reads as
     * untimed. The same tell catches mark-less UTF-16: real `.lrc`
     * text never contains NUL, so a decode that has them is retried
     * as UTF-16 (little-endian first, the common save) and the clean
     * decode wins.
     */
    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return decodeUtf16(bytes, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return decodeUtf16(bytes, Charsets.UTF_16BE)
        }
        // Every strict attempt is failure-tolerant: a candidate that
        // does not decode cleanly is skipped, never thrown — the
        // first version of this chain let the GBK fallback itself
        // throw on mark-less UTF-16 bytes (malformed as GBK too),
        // which escaped as an exception instead of reaching the
        // UTF-16 retry below.
        val utf8Text = strictDecode(bytes, Charsets.UTF_8)
        if (utf8Text != null && utf8Text.indexOf('\u0000') < 0) {
            return utf8Text.removePrefix("\uFEFF")
        }
        val gbkText = strictDecode(bytes, Charset.forName("GBK"))
        if (gbkText != null && gbkText.indexOf('\u0000') < 0) {
            return gbkText.removePrefix("\uFEFF")
        }
        // A decode that "succeeded" but is full of NULs was UTF-16
        // without a mark (or bytes neither UTF-8 nor GBK fits). The
        // timestamps every .lrc carries are ASCII, so the GBK read
        // of UTF-16 bytes is NUL-riddled too and never wins above;
        // the clean UTF-16 retry does.
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val retried = strictDecode(bytes, charset) ?: continue
            if (retried.indexOf('\u0000') < 0) return retried.removePrefix("\uFEFF")
        }
        // Nothing decoded cleanly: a lossy UTF-8 read rather than an
        // exception — a corrupt file parses to no timed lines and is
        // simply not shown.
        return utf8Text?.removePrefix("\uFEFF") ?: String(bytes, Charsets.UTF_8)
    }

    /** Strictly decodes, or null when the bytes are not valid in [charset]. */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? =
        try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    /** Decodes UTF-16 bytes in the given endianness, dropping the decoded mark. */
    private fun decodeUtf16(bytes: ByteArray, charset: Charset): String =
        String(bytes, charset).removePrefix("\uFEFF")

    /**
     * Parses `.lrc` bytes into a [LyricsDocument], or null when the file has
     * no timed lines. Untimed files are not shown: a timed file that failed
     * to parse must not be shown raw either.
     */
    fun parseBytes(bytes: ByteArray, name: String): LyricsDocument? {
        val lines = LrcParser.parse(decodeBytes(bytes))
        if (lines.isEmpty()) return null
        return LyricsDocument(lines = lines, sourceName = name)
    }

    /**
     * Audio-anchored match: the `.lrc` whose stem is exactly the playing
     * audio file's stem. Returns null when there is no audio file or no
     * same-stem `.lrc`. The duration tie-break needs parsed files, so it
     * lives in the provider ([pickWinner]), not here.
     */
    fun matchLrc(
        lrcFiles: List<LocalLrcFile>,
        audioFile: LocalAudioFile?,
    ): LocalLrcFile? {
        if (audioFile == null) return null
        val indexedAudio = IndexedAudioFile(audioFile, fileStem(audioFile.fileName))
        val indexedLrc = lrcFiles.map { IndexedLrcFile(it, fileKeys(it.fileName)) }
        return sameStemLrc(indexedAudio, indexedLrc)?.file
    }

    /**
     * Metadata match: the best `.lrc` for a track by title and artists.
     * Returns the first file in match order (best key tier, index order);
     * the duration tie-break needs parsed files, so it lives in the provider
     * ([pickWinner]), not here.
     */
    fun matchLrc(
        lrcFiles: List<LocalLrcFile>,
        title: String,
        artists: List<String>,
        durationMs: Long,
    ): LocalLrcFile? {
        val indexed = lrcFiles.map { IndexedLrcFile(it, fileKeys(it.fileName)) }
        return match(title, artists, indexed).firstOrNull()?.file
    }
}
