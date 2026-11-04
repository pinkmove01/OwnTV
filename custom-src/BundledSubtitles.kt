package tv.own.owntv.player

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 직접경로(a): /storage/emulated/0/Download/자막 폴더를 읽기 전용으로 스캔해
 * 재생 제목에 맞는 자막 1개를 자동 첨부한다. assets/subtitles 는 사용하지 않는다.
 *
 * 정규화 규칙은 폰의 submatch.sh 와 동일:
 *   확장자 제거 -> ASCII ' ()[]{}.,;:!?_~' 제거 -> 소문자 (하이픈 유지, 한글 그대로)
 * 중복 키는 파일크기 큰 쪽, 크기 같으면 이름 짧은 쪽 1개만 남긴다.
 */
object BundledSubtitles {
    private const val TAG = "SubMatch"

    // 기기별 후보 경로 — 처음 발견되는 폴더를 쓴다 (셋톱박스 대비)
    private val FOLDERS = listOf(
        "/storage/emulated/0/Download/자막",
        "/sdcard/Download/자막",
        "/storage/emulated/0/Download/Subtitles",
    )
    private val EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "smi")
    private val STRIP = " ()[]{}.,;:!?_~".toCharArray()
    private val YEAR = Regex("(19|20)\\d{2}")

    /** SubtitleController.autoAttachProvider 에 꽂히는 훅. */
    fun provider(context: Context): (String?) -> List<OwnTVPlayer.ExternalSub> =
        { title -> match(title) }

    fun match(title: String?): List<OwnTVPlayer.ExternalSub> {
        val dir = FOLDERS.map(::File).firstOrNull { it.isDirectory && it.canRead() }
        if (dir == null) { Log.i(TAG, "자막 폴더 없음/권한 없음"); return emptyList() }
        if (title.isNullOrBlank()) return emptyList()

        val index = index(dir)
        if (index.isEmpty()) { Log.i(TAG, "빈 인덱스: ${dir.absolutePath}"); return emptyList() }

        val key = normalize(title)
        if (key.isEmpty()) return emptyList()

        val hit = resolve(index, key)
        if (hit == null) { Log.i(TAG, "NOMATCH $title"); return emptyList() }

        val (file, kind) = hit
        Log.i(TAG, "$kind $title -> ${file.name}")
        return listOf(
            OwnTVPlayer.ExternalSub(
                path = file.absolutePath,
                title = file.nameWithoutExtension,
                lang = null,
                source = ExternalSubtitleSource.LOCAL,
            ),
        )
    }

    /** 정규화키 -> 파일 1개. 크기 큰 쪽, 같으면 이름 짧은 쪽. */
    private fun index(dir: File): Map<String, File> {
        val out = HashMap<String, File>()
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.extension.lowercase() !in EXTENSIONS) return@forEach
            val k = normalize(f.nameWithoutExtension)
            if (k.isEmpty()) return@forEach
            val cur = out[k]
            when {
                cur == null -> out[k] = f
                f.length() > cur.length() -> out[k] = f
                f.length() == cur.length() && f.name.length < cur.name.length -> out[k] = f
            }
        }
        return out
    }

    /** EXACT -> PARTIAL(연도 키 우선 -> 가장 긴 키). 동률이면 null = 붙이지 않음. */
    private fun resolve(index: Map<String, File>, key: String): Pair<File, String>? {
        index[key]?.let { return it to "EXACT" }

        val cands = index.filterKeys { it.contains(key) || key.contains(it) }
        if (cands.isEmpty()) return null
        if (cands.size == 1) return cands.values.first() to "PARTIAL"

        val withYear = cands.filterKeys { YEAR.containsMatchIn(it) }
        val pool = if (withYear.isNotEmpty()) withYear else cands
        if (pool.size == 1) return pool.values.first() to "PARTIAL"

        val longest = pool.keys.maxOf { it.length }
        val best = pool.filterKeys { it.length == longest }
        if (best.size == 1) return best.values.first() to "PARTIAL"

        Log.i(TAG, "AMBIGUOUS $key (${pool.size}개) -> 자동 첨부 안 함")
        return null
    }

    /** submatch.sh 와 동일한 키 생성. ASCII 구두점만 제거, 그 외 문자(한글)는 보존. */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            if (c.code > 127) { sb.append(c); continue }
            if (STRIP.contains(c)) continue
            sb.append(c)
        }
        return sb.toString()
    }
}
