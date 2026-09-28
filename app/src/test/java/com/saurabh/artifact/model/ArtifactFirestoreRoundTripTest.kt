package com.saurabh.artifact.model

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.PropertyName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.primaryConstructor

class ArtifactFirestoreRoundTripTest {

    @Test
    fun `verify isDraftField annotations`() {
        val klass = Artifact::class
        val prop = klass.members.find { it.name == "isDraftField" } as? KProperty<*>
        
        // 1. Verify PropertyName annotation on getter
        val getterAnnotation = prop?.getter?.annotations?.find { it is PropertyName } as? PropertyName
        assertEquals("isDraft", getterAnnotation?.value)

        // 2. Verify PropertyName annotation on field
        val field = Artifact::class.java.getDeclaredField("isDraftField")
        val fieldAnnotation = field.getAnnotation(PropertyName::class.java)
        assertEquals("isDraft", fieldAnnotation?.value)
    }

    @Test
    fun `verify episodeNumber annotations`() {
        val klass = Artifact::class
        val prop = klass.members.find { it.name == "episodeNumber" } as? KProperty<*>
        
        // 1. Verify PropertyName annotation on getter
        val getterAnnotation = prop?.getter?.annotations?.find { it is PropertyName } as? PropertyName
        assertEquals("episodeNumber", getterAnnotation?.value)

        // 2. Verify PropertyName annotation on setter
        val setterAnnotation = (prop as? KMutableProperty<*>)?.setter?.annotations?.find { it is PropertyName } as? PropertyName
        assertEquals("episodeNumber", setterAnnotation?.value)

        // 3. Verify PropertyName annotation on field
        val field = Artifact::class.java.getDeclaredField("episodeNumber")
        val fieldAnnotation = field.getAnnotation(PropertyName::class.java)
        assertEquals("episodeNumber", fieldAnnotation?.value)
    }

    @Test
    fun `verify Firestore CustomClassMapper hydrates episodeNumber correctly`() {
        val map = mapOf<String, Any>(
            "id" to "test_doc_35",
            "title" to "Artifact",
            "episodeNumber" to 35L,
        )
        val customClassMapper = Class.forName("com.google.firebase.firestore.util.CustomClassMapper")
        val method = customClassMapper.getDeclaredMethod(
            "convertToCustomClass",
            Any::class.java,
            Class::class.java,
            DocumentReference::class.java,
        )
        method.isAccessible = true
        val artifact = method.invoke(null, map, Artifact::class.java, null) as Artifact
        assertEquals(35L, artifact.episodeNumber)
    }

    @Test
    fun `verify Firestore CustomClassMapper handles legacy artifact without episodeNumber field safely`() {
        val map = mapOf<String, Any>(
            "id" to "legacy_doc_1",
            "title" to "Artifact",
        )
        val customClassMapper = Class.forName("com.google.firebase.firestore.util.CustomClassMapper")
        val method = customClassMapper.getDeclaredMethod(
            "convertToCustomClass",
            Any::class.java,
            Class::class.java,
            DocumentReference::class.java,
        )
        method.isAccessible = true
        val artifact = method.invoke(null, map, Artifact::class.java, null) as Artifact
        assertNull(artifact.episodeNumber)
    }

    @Test
    fun `verify isDraft computed property is excluded`() {
        val klass = Artifact::class
        val prop = klass.members.find { it.name == "isDraft" } as? KProperty<*>
        
        val hasExclude = prop?.getter?.annotations?.any { 
            it.annotationClass.simpleName == "Exclude" 
        } ?: false
        
        assertTrue("isDraft computed property should be excluded from getter", hasExclude)
    }

    @Test
    fun `verify Artifact constructor has expected parameters`() {
        // This test ensures the constructor remains stable for Firestore reflection
        val constructor = Artifact::class.primaryConstructor
        val params = constructor?.parameters
        
        val isDraftFieldParam = params?.find { it.name == "isDraftField" }
        assertTrue("Constructor should have isDraftField parameter", isDraftFieldParam != null)
    }
}
