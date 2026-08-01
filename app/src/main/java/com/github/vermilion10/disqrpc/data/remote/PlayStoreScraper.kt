package com.github.vermilion10.disqrpc.data.remote

import org.jsoup.Jsoup
import java.io.IOException

object PlayStoreScraper {
    private const val BASE_URL = "https://play.google.com/store/apps/details?id="

    data class AppMetadata(
        val name: String,
        val iconUrl: String
    )

    fun fetchMetadata(packageName: String): AppMetadata? {
        return try {
            val doc = Jsoup.connect("$BASE_URL$packageName").get()
            
            // Note: Google Play Store's HTML structure changes frequently.
            // Prefer Open Graph meta tags, which are reliably present on details pages.
            val name = doc.select("h1[itemprop='name'] span").text().ifEmpty {
                doc.select("h1[itemprop='name']").text().ifEmpty {
                    doc.select("h1").first()?.text() ?: ""
                }
            }

            val iconUrl = doc.select("meta[property='og:image']").attr("content").ifEmpty {
                doc.select("img[alt='Icon image']").attr("src").ifEmpty {
                    doc.select("img[title]").first()?.attr("src") ?: ""
                }
            }

            if (name.isNotEmpty()) {
                AppMetadata(name, iconUrl)
            } else {
                null
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
}
