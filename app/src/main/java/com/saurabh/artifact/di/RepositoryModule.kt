package com.saurabh.artifact.di

import com.saurabh.artifact.repository.CommentRepository
import com.saurabh.artifact.repository.FirestoreCommentRepository
import com.saurabh.artifact.repository.TopicRepository
import com.saurabh.artifact.repository.TopicRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class RepositoryModule {

    @Binds
    @Singleton
    @Suppress("unused")
    abstract fun bindTopicRepository(
        topicRepositoryImpl: TopicRepositoryImpl,
    ): TopicRepository

    @Binds
    @Singleton
    @Suppress("unused")
    abstract fun bindCommentRepository(
        impl: FirestoreCommentRepository
    ): CommentRepository
}
