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
        // Dominant axis: 4000 -> halve until <= 512 => 8 (longest edge lands
        // at ~500, just under the target).
        val sampleSize = GalleryViewModel.calculateInSampleSize(opts, 512, 512)
        assertEquals(8, sampleSize)
    }

    @Test
    fun `calculateInSampleSize handles very large images`() {
        val opts = BitmapFactory.Options().apply {
            outWidth = 8000
            outHeight = 6000
        }
        // Dominant axis: 8000 -> halve until <= 512 => 16 (longest edge ~500).
        val sampleSize = GalleryViewModel.calculateInSampleSize(opts, 512, 512)
        assertEquals(16, sampleSize)
    }

    @Test
    fun `calculateInSampleSize handles tall images`() {
        // Downsample on the dominant axis so a single oversized dimension no
        // longer produces a full-resolution decode (the old both-dimensions
        // algorithm returned 1 here, yielding full-res thumbnails).
        val opts = BitmapFactory.Options().apply {
            outWidth = 500
            outHeight = 5000
        }
        val sampleSize = GalleryViewModel.calculateInSampleSize(opts, 512, 512)
        assertEquals(16, sampleSize)
    }

    @Test
    fun `calculateInSampleSize handles wide images`() {
        // Same as tall: the dominant (wide) axis drives downsampling.
        val opts = BitmapFactory.Options().apply {
            outWidth = 5000
            outHeight = 500
        }
        val sampleSize = GalleryViewModel.calculateInSampleSize(opts, 512, 512)
        assertEquals(16, sampleSize)
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
