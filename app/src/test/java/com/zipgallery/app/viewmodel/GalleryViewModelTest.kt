package com.zipgallery.app.viewmodel

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryViewModelTest {

    @Test
    fun `calculateInSampleSize returns 1 for small images`() {
        val opts = BitmapFactory.Options().apply {
            outWidth = 100
            outHeight = 100
        }
        val sampleSize = GalleryViewModel.calculateInSampleSize(opts, 512, 512)
        assertEquals(1, sampleSize)
    }

    @Test
    fun `calculateInSampleSize returns appropriate size for large images`() {
        val opts = BitmapFactory.Options().apply {
            outWidth = 4000
            outHeight = 3000
        }
        val sampleSize = GalleryViewModel.calculateInSampleSize(opts, 512, 512)
        assertEquals(4, sampleSize)
    }

    @Test
    fun `calculateInSampleSize handles very large images`() {
        val opts = BitmapFactory.Options().apply {
            outWidth = 8000
            outHeight = 6000
        }
        val sampleSize = GalleryViewModel.calculateInSampleSize(opts, 512, 512)
        assertEquals(8, sampleSize)
    }

    @Test
    fun `calculateInSampleSize handles tall images`() {
        val opts = BitmapFactory.Options().apply {
            outWidth = 500
            outHeight = 5000
        }
        val sampleSize = GalleryViewModel.calculateInSampleSize(opts, 512, 512)
        assertEquals(4, sampleSize)
    }

    @Test
    fun `calculateInSampleSize handles wide images`() {
        val opts = BitmapFactory.Options().apply {
            outWidth = 5000
            outHeight = 500
        }
        val sampleSize = GalleryViewModel.calculateInSampleSize(opts, 512, 512)
        assertEquals(4, sampleSize)
    }

    @Test
    fun `calculateInSampleSize handles zero dimensions`() {
        val opts = BitmapFactory.Options().apply {
            outWidth = 0
            outHeight = 0
        }
        val sampleSize = GalleryViewModel.calculateInSampleSize(opts, 512, 512)
        assertEquals(1, sampleSize)
    }
}
