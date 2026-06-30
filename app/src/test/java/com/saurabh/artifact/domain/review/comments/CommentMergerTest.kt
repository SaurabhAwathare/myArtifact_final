package com.saurabh.artifact.domain.review.comments

import com.google.firebase.Timestamp
import com.saurabh.artifact.model.ArtifactComment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class CommentMergerTest {

    private val merger = CommentMerger()

    @Test
    fun `merge should deduplicate comments by id`() {
        val comment1 = ArtifactComment(id = "1", content = "A", createdAt = Timestamp(Date(1000)))
        val comment2 = ArtifactComment(id = "1", content = "B", createdAt = Timestamp(Date(1000))) // Duplicate ID
        
        val result = merger.merge(listOf(comment1), listOf(comment2))
        
        assertEquals(1, result.size)
        assertEquals("1", result[0].id)
    }

    @Test
    fun `merge should sort comments by createdAt descending`() {
        val old = ArtifactComment(id = "old", createdAt = Timestamp(Date(1000)))
        val new = ArtifactComment(id = "new", createdAt = Timestamp(Date(2000)))
        
        val result = merger.merge(listOf(old), listOf(new))
        
        assertEquals(2, result.size)
        assertEquals("new", result[0].id)
        assertEquals("old", result[1].id)
    }

    @Test
    fun `merge should handle empty lists`() {
        val result = merger.merge(emptyList(), emptyList())
        assertEquals(0, result.size)
    }
}
