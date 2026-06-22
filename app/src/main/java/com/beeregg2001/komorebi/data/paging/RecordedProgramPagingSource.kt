package com.beeregg2001.komorebi.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.RecordProvider

class RecordedProgramPagingSource(
    private val recordProvider: RecordProvider,
    private val query: String,
    private val order: String
) : PagingSource<Int, RecordedProgram>() {

    private companion object {
        const val SERVER_PAGE_SIZE = 30
    }

    override fun getRefreshKey(state: PagingState<Int, RecordedProgram>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null
        return anchorPage.prevKey?.plus(1) ?: anchorPage.nextKey?.minus(1)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, RecordedProgram> {
        val page = params.key ?: 1
        return try {
            val response = if (query.isBlank()) {
                recordProvider.getRecordedPrograms(page = page, order = order)
            } else {
                recordProvider.searchRecordedPrograms(keyword = query, page = page, order = order)
            }
            val programs = response.recordedPrograms
            val total = response.total
            val reachedEnd =
                programs.size < SERVER_PAGE_SIZE || (total > 0 && page * SERVER_PAGE_SIZE >= total)

            LoadResult.Page(
                data = programs,
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (reachedEnd) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
