package com.lagradost.cloudstream3.ui.home

import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching
import com.lagradost.cloudstream3.utils.AppContextUtils.filterHomePageListByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.FamilyMode
import com.lagradost.cloudstream3.utils.DataStoreHelper.deleteAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllWatchStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getBookmarkedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount
import com.lagradost.cloudstream3.utils.DataStoreHelper.getLastWatched
import com.lagradost.cloudstream3.utils.DataStoreHelper.getResultWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList

class HomeViewModel : ViewModel() {
    companion object {
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? {
            val resumeWatching = withContext(Dispatchers.IO) {
                getAllResumeStateIds()?.mapNotNull { id ->
                    getLastWatched(id)
                }?.sortedBy { -it.updateTime }
            }
            val resumeWatchingResult = withContext(Dispatchers.IO) {
                resumeWatching?.mapNotNull { resume ->
                    val headerCache = getKey<DownloadObjects.DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE,
                        resume.parentId.toString()
                    )

                    val data = if (headerCache == null) {
                        // We store resume watching data in download header cache
                        // Because downloads automatically pruned outdated download headers we
                        // removed resume watching data. We should restore the data for affected users.
                        val oldData = getKey<DownloadObjects.DownloadHeaderCached>(
                            DOWNLOAD_HEADER_CACHE_BACKUP,
                            resume.parentId.toString()
                        ) ?: return@mapNotNull null

                        // Restore data
                        setKey(DOWNLOAD_HEADER_CACHE, resume.parentId.toString(), oldData)
                        oldData
                    } else {
                        headerCache
                    }

                    val watchPos = getViewPos(resume.episodeId)

                    DataStoreHelper.ResumeWatchingResult(
                        data.name,
                        data.url,
                        data.apiName,
                        data.type,
                        data.poster,
                        watchPos,
                        resume.episodeId,
                        resume.parentId,
                        resume.episode,
                        resume.season,
                        resume.isFromDownload
                    )
                }
            }
            return resumeWatchingResult
        }
    }

    fun deleteResumeWatching() {
        deleteAllResumeStateIds()
        loadResumeWatching()
    }

    fun deleteBookmarks(list: List<SearchResponse>) {
        list.forEach { DataStoreHelper.deleteBookmarkedData(it.id) }
        loadStoredData()
    }

    var repo: APIRepository? = null

    private val _apiName = MutableLiveData<String>()
    val apiName: LiveData<String> = _apiName

    private val _currentAccount = MutableLiveData<DataStoreHelper.Account?>()
    val currentAccount: MutableLiveData<DataStoreHelper.Account?> = _currentAccount

    private val _randomItems = MutableLiveData<List<SearchResponse>?>(null)
    val randomItems: LiveData<List<SearchResponse>?> = _randomItems

    private var currentShuffledList: List<SearchResponse> = listOf()

    private fun autoloadRepo(): APIRepository {
        return APIRepository(apis.withLock { apis.first { it.hasMainPage } })
    }

    private val _availableWatchStatusTypes =
        MutableLiveData<Pair<Set<WatchType>, Set<WatchType>>>()
    val availableWatchStatusTypes: LiveData<Pair<Set<WatchType>, Set<WatchType>>> =
        _availableWatchStatusTypes
    private val _bookmarks = MutableLiveData<Pair<Boolean, List<SearchResponse>>>()
    val bookmarks: LiveData<Pair<Boolean, List<SearchResponse>>> = _bookmarks

    private val _resumeWatching = MutableLiveData<List<SearchResponse>>()
    private val _preview = MutableLiveData<Resource<Pair<Boolean, List<LoadResponse>>>>()
    private val previewResponses = CopyOnWriteArrayList<LoadResponse>()
    private val previewResponsesAdded = mutableSetOf<String>()

    val resumeWatching: LiveData<List<SearchResponse>> = _resumeWatching
    val preview: LiveData<Resource<Pair<Boolean, List<LoadResponse>>>> = _preview

    private fun loadResumeWatching() = viewModelScope.launchSafe {
        val resumeWatchingResult = getResumeWatching()
        if (isLayout(TV) && resumeWatchingResult != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ioSafe {
                // this WILL crash on non tvs, so keep this inside a try catch
                activity?.addProgramsToContinueWatching(resumeWatchingResult)
            }
        }
        resumeWatchingResult?.let {
            _resumeWatching.postValue(it)
        }
    }

    fun loadStoredData(preferredWatchStatus: Set<WatchType>?) = viewModelScope.launchSafe {
        val watchStatusIds = withContext(Dispatchers.IO) {
            getAllWatchStateIds()?.map { id ->
                Pair(id, getResultWatchState(id))
            }
        }?.distinctBy { it.first } ?: return@launchSafe

        val length = WatchType.entries.size
        val currentWatchTypes = mutableSetOf<WatchType>()

        for (watch in watchStatusIds) {
            currentWatchTypes.add(watch.second)
            if (currentWatchTypes.size >= length) {
                break
            }
        }

        currentWatchTypes.remove(WatchType.NONE)

        if (currentWatchTypes.size <= 0) {
            DataStoreHelper.homeBookmarkedList = intArrayOf()
            _availableWatchStatusTypes.postValue(setOf<WatchType>() to setOf())
            _bookmarks.postValue(Pair(false, ArrayList()))
            return@launchSafe
        }

        // Phase 8.71: resolve every bookmark once; the strip below
        // picks from this list. Previously the strip defaulted to one
        // arbitrary watch type, so a list added from the Feed often
        // surfaced as a bare category bar whose posters needed a tap
        // into a popup page (his report).
        val allBookmarks = withContext(Dispatchers.IO) {
            watchStatusIds
                .mapNotNull { (id, type) ->
                    if (type == WatchType.NONE) {
                        null
                    } else {
                        getBookmarkedData(id)?.let { type to it }
                    }
                }
                .sortedBy { -it.second.latestUpdatedTime }
        }

        // Default selection: the type of the most recently updated
        // bookmark, so a just-added item's posters show on Home at
        // once instead of hiding behind another type's filter.
        val watchPrefNotNull = preferredWatchStatus
            ?: allBookmarks.firstOrNull()?.let { EnumSet.of(it.first) }
            ?: EnumSet.of(currentWatchTypes.first())
        //if (currentWatchTypes.any { watchPrefNotNull.contains(it) }) watchPrefNotNull else listOf(currentWatchTypes.first())

        DataStoreHelper.homeBookmarkedList = watchPrefNotNull.map { it.internalId }.toIntArray()
        _availableWatchStatusTypes.postValue(

            watchPrefNotNull to
                    currentWatchTypes,

            )

        val filtered = allBookmarks
            .filter { watchPrefNotNull.contains(it.first) }
            .map { it.second }
        // Passive rebuilds (no explicit chip choice) fall back to
        // every bookmark when the selected type has none, so posters
        // stay visible on Home directly. Explicit chip taps keep
        // their exact filter.
        val list = if (filtered.isNotEmpty() || preferredWatchStatus != null) {
            filtered
        } else {
            allBookmarks.map { it.second }
        }
        _bookmarks.postValue(Pair(true, list))
    }

    private var onGoingLoad: Job? = null
    private var isCurrentlyLoadingName: String? = null
    private fun loadAndCancel(api: MainAPI) {
        //println("loaded ${api.name}")
        onGoingLoad?.cancel()
        isCurrentlyLoadingName = api.name
        onGoingLoad = load(api)
    }

    data class ExpandableHomepageList(
        var list: HomePageList,
        var currentPage: Int,
        var hasNext: Boolean,
    )

    private val expandable: MutableMap<String, ExpandableHomepageList> = mutableMapOf()
    private val _page =
        MutableLiveData<Resource<Map<String, ExpandableHomepageList>>>(Resource.Loading())
    val page: LiveData<Resource<Map<String, ExpandableHomepageList>>> = _page

    val lock: MutableSet<String> = mutableSetOf()

    // Phase 8.44: custom home lists — rows served by several
    // providers. customRoutes maps a row's unique display name to
    // (provider repo, main-page index, real page name).
    data class CustomRoute(
        val repo: APIRepository,
        val pageIndex: Int?,
        val pageName: String,
        val dynamic: Boolean
    )

    val customRoutes = mutableMapOf<String, CustomRoute>()

    @Volatile
    var loadedCustomSignature: String? = null
        private set

    val isCustomActive: Boolean get() = customRoutes.isNotEmpty()

    // Batch 3: the expanded sheet submits expandAndReturn's
    // lists raw (HomeFragment's scroll listener), bypassing the
    // applyHomeCategories() filter the main rows go through —
    // so page-2+ items, including adult items filtered from the
    // main row, leaked into the sheet when Family Mode was on.
    // Filtering here, on both expand paths, covers the sheet
    // and every _page observer. The underlying lists are
    // rebuilt on the next load if Family Mode is turned off.
    private fun filterExpandedForFamily() {
        if (!FamilyMode.isEnabled()) return
        expandable.values.forEach { entry ->
            entry.list.list = entry.list.list.filterNot {
                FamilyMode.isAdultItem(it)
            }
        }
    }

    suspend fun expandAndReturn(name: String): ExpandableHomepageList? {
        if (lock.contains(name)) return null
        lock += name

        // Phase 8.44: a custom-list row expands through its OWN
        // provider, not the single home repo.
        val customRoute = customRoutes[name]
        if (customRoute != null) {
            val repoX = customRoute.repo
            val pageIndex = customRoute.pageIndex
            val pageName = customRoute.pageName
            expandable[name]?.let { current ->
                if (current.hasNext) {
                    val nextPage = current.currentPage + 1
                    val next = repoX.getMainPage(nextPage, pageIndex)
                    if (next is Resource.Success) {
                        next.value.filterNotNull().forEach { main ->
                            val match = main.items.firstOrNull {
                                it.name == pageName
                            } ?: main.items.singleOrNull()
                            if (match != null) {
                                current.list.list =
                                    (current.list.list + match.list)
                                        .distinctBy { it.url }
                            }
                            current.hasNext = main.hasNext
                            current.currentPage = nextPage
                        }
                    } else {
                        current.hasNext = false
                    }
                }
            }
            filterExpandedForFamily()
            _page.postValue(Resource.Success(expandable))
            lock -= name
            return expandable[name]
        }

        repo?.apply {
            waitForHomeDelay()

            expandable[name]?.let { current ->
                debugAssert({ !current.hasNext }) {
                    "Expand called when not needed"
                }

                val nextPage = current.currentPage + 1
                val next = getMainPage(nextPage, mainPage.indexOfFirst { it.name == name })
                if (next is Resource.Success) {
                    next.value.filterNotNull().forEach { main ->
                        main.items.forEach { newList ->
                            val key = newList.name
                            expandable[key]?.apply {
                                hasNext = main.hasNext
                                currentPage = nextPage

                                debugWarning({ newList.list.any { outer -> this.list.list.any { it.url == outer.url } } }) {
                                    "Expanded contained an item that was previously already in the list\n${list.name} = ${this.list.list}\n${newList.name} = ${newList.list}"
                                }

                                this.list.list += newList.list
                                this.list.list.distinctBy { it.url } // just to be sure we are not adding the same shit for some reason
                            } ?: debugWarning {
                                "Expanded an item not in main load named $key, current list is ${expandable.keys}"
                            }
                        }
                    }
                } else {
                    current.hasNext = false
                }
            }
            filterExpandedForFamily()
            _page.postValue(Resource.Success(expandable))
        }

        lock -= name

        return expandable[name]
    }

    // this is soo over engineered, but idk how I can make it clean without making the main api harder to use :pensive:
    fun expand(name: String) = viewModelScope.launchSafe {
        expandAndReturn(name)
    }

    // returns the amount of items added and modifies current
    private suspend fun updatePreviewResponses(
        current: MutableList<LoadResponse>,
        alreadyAdded: MutableSet<String>,
        shuffledList: List<SearchResponse>,
        size: Int
    ): Int {
        var count = 0

        val addItems = arrayListOf<SearchResponse>()
        for (searchResponse in shuffledList) {
            if (!alreadyAdded.contains(searchResponse.url)) {
                addItems.add(searchResponse)
                previewResponsesAdded.add(searchResponse.url)
                if (++count >= size) {
                    break
                }
            }
        }

        val add = addItems.amap { searchResponse ->
            repo?.load(searchResponse.url)
        }.mapNotNull { if (it != null && it is Resource.Success) it.value else null }
        current.addAll(add)
        return add.size
    }

    private var addJob: Job? = null
    fun loadMoreHomeScrollResponses() {
        addJob = ioSafe {
            updatePreviewResponses(previewResponses, previewResponsesAdded, currentShuffledList, 1)
            _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
        }
    }

    private fun load(api: MainAPI): Job = ioSafe {
        repo = //if (api != null) {
            APIRepository(api)
        //} else {
        //    autoloadRepo()
        //}

        _apiName.postValue(repo?.name)
        _randomItems.postValue(listOf())

        if (repo?.hasMainPage != true) {
            _page.postValue(Resource.Success(emptyMap()))
            _preview.postValue(Resource.Failure(false, "No homepage"))
            return@ioSafe
        }


        _page.postValue(Resource.Loading())
        _preview.postValue(Resource.Loading())
        // cancel the current preview expand as that is no longer relevant
        addJob?.cancel()

        when (val data = repo?.getMainPage(1, null)) {
            is Resource.Success -> {
                try {
                    expandable.clear()
                    data.value.forEach { home ->
                        home?.items?.forEach { list ->
                            val filteredList =
                                context?.filterHomePageListByFilmQuality(list) ?: list
                            // Phase 8.17: rotate each category by a random
                            // offset on every fresh load, so a refresh leads
                            // with different posters instead of always the
                            // same ones. Expansions append to this same list
                            // afterwards, so the order stays stable while
                            // browsing deeper.
                            val rotated = if (filteredList.list.size >= 4) {
                                val offset = (1 until filteredList.list.size).random()
                                filteredList.list.drop(offset) +
                                    filteredList.list.take(offset)
                            } else {
                                filteredList.list
                            }
                            expandable[list.name] =
                                ExpandableHomepageList(
                                    filteredList.copy(
                                        list = CopyOnWriteArrayList(rotated)
                                    ), 1, home.hasNext
                                )
                        }
                    }

                    val items = data.value.mapNotNull { it?.items }.flatten()


                    // Phase 8.16: remember which poster opens the hero,
                    // so a refresh never deals the same opener twice in a
                    // row (the list itself is reshuffled on every load).
                    val previousPreviewFirstUrl = previewResponses.firstOrNull()?.url

                    previewResponses.clear()
                    previewResponsesAdded.clear()

                    //val home = data.value
                    if (items.isNotEmpty()) {
                        // Phase 8.50: Family Mode keeps NSFW items
                        // out of the hero pool and random strip.
                        val familyOn = FamilyMode.isEnabled()
                        val currentList =
                            items.shuffled().filter { it.list.isNotEmpty() }
                                .flatMap { it.list }
                                .distinctBy { it.url }
                                .filterNot {
                                    familyOn && FamilyMode.isAdultItem(it)
                                }
                                .toList()

                        if (currentList.isNotEmpty()) {
                            val randomItems =
                                context?.filterSearchResultByFilmQuality(currentList.shuffled())
                                    ?: currentList.shuffled()

                            updatePreviewResponses(
                                previewResponses,
                                previewResponsesAdded,
                                randomItems,
                                3
                            )

                            _randomItems.postValue(randomItems)
                            currentShuffledList = randomItems
                        }
                    }
                    // Phase 8.16: if the fresh shuffle still opens on the
                    // same poster as last time, rotate it to the back — the
                    // hero visibly re-deals on every refresh.
                    if (previousPreviewFirstUrl != null && previewResponses.size > 1 &&
                        previewResponses.firstOrNull()?.url == previousPreviewFirstUrl
                    ) {
                        previewResponses.add(previewResponses.removeAt(0))
                    }
                    if (previewResponses.isEmpty()) {
                        _preview.postValue(
                            Resource.Failure(
                                false,
                                "No homepage responses"
                            )
                        )
                    } else {
                        _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
                    }
                    _page.postValue(Resource.Success(expandable))
                } catch (e: Exception) {
                    _randomItems.postValue(emptyList())
                    logError(e)
                }
            }

            is Resource.Failure -> {
                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                _page.postValue(data!!)
                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                _preview.postValue(data!!)
            }

            else -> Unit
        }
        isCurrentlyLoadingName = null
    }

    // ---------- Phase 8.44: custom home lists ----------

    private fun customSignature(list: HomeCustomLists.CustomList): String =
        list.id + "|" + list.entries.joinToString(",") {
            it.apiName + ":" + it.pageName + ":" + it.dynamic
        }

    fun activateCustomList(list: HomeCustomLists.CustomList) {
        context?.let { HomeCustomLists.setActiveId(it, list.id) }
        loadCustomList(list)
    }

    fun exitCustomMode() {
        context?.let { HomeCustomLists.setActiveId(it, null) }
        customRoutes.clear()
        loadedCustomSignature = null
    }

    fun loadCustomList(list: HomeCustomLists.CustomList) {
        onGoingLoad?.cancel()
        isCurrentlyLoadingName = "custom:" + list.id
        onGoingLoad = ioSafe {
            // Phase 8.75: publish the list's name as the current
            // "api" name — the provider switcher label is driven by
            // apiName, and custom loads never posted it, so the
            // switcher sat blank while a custom list was active.
            _apiName.postValue(list.name)
            _page.postValue(Resource.Loading())
            _preview.postValue(Resource.Loading())
            customRoutes.clear()
            val nameCounts =
                list.entries.groupingBy { it.pageName }.eachCount()
            val built =
                LinkedHashMap<String, ExpandableHomepageList>()
            // Batch 3: an entry skipped here (provider not
            // loaded yet, no main page, failed load, or no
            // matching row) must not be pinned as complete —
            // see the signature gate below.
            var allEntriesResolved = true
            for (entry in list.entries) {
                val builtBefore = built.size
                try {
                    val api = getApiFromNameNull(entry.apiName)
                    if (api == null) {
                        allEntriesResolved = false
                        continue
                    }
                    val repoX = APIRepository(api)
                    if (!repoX.hasMainPage) {
                        allEntriesResolved = false
                        continue
                    }
                    // Phase 8.46: dynamic entries (provider
                    // declares no mainPage) load the whole
                    // homepage and pick their category by name;
                    // declared entries load by page index.
                    val idx = if (entry.dynamic) {
                        -1
                    } else {
                        api.mainPage.indexOfFirst {
                            it.name == entry.pageName
                        }
                    }
                    if (!entry.dynamic && idx < 0) {
                        allEntriesResolved = false
                        continue
                    }
                    val res = if (entry.dynamic) {
                        repoX.getMainPage(1, null)
                    } else {
                        repoX.getMainPage(1, idx)
                    }
                    if (res !is Resource.Success) {
                        allEntriesResolved = false
                        continue
                    }
                    res.value.filterNotNull().forEach { home ->
                        home.items.filter { it.name == entry.pageName }
                            .forEach { hl ->
                                val filtered =
                                    context?.filterHomePageListByFilmQuality(hl)
                                        ?: hl
                                val display =
                                    if ((nameCounts[entry.pageName] ?: 0) > 1) {
                                        "${entry.pageName} • ${entry.apiName}"
                                    } else {
                                        entry.pageName
                                    }
                                built[display] = ExpandableHomepageList(
                                    filtered.copy(
                                        name = display,
                                        list = CopyOnWriteArrayList(filtered.list)
                                    ),
                                    1,
                                    home.hasNext
                                )
                                customRoutes[display] = CustomRoute(
                                    repoX,
                                    if (entry.dynamic) null else idx,
                                    entry.pageName,
                                    entry.dynamic
                                )
                            }
                    }
                } catch (e: Exception) {
                    logError(e)
                    allEntriesResolved = false
                }
                // A successful load that contains no row for
                // this entry still leaves the entry missing.
                if (built.size == builtBefore) allEntriesResolved = false
            }
            expandable.clear()
            expandable.putAll(built)
            if (built.isEmpty()) {
                // Providers may still be loading; stay in Loading
                // so a later load call rebuilds instead of pinning
                // an empty home.
                _page.postValue(Resource.Loading())
                return@ioSafe
            }
            // Batch 3: only pin the signature when every entry
            // resolved. A partial load still posts Success with
            // the rows it has, but leaving the signature unset
            // means reloadStored() does not early-return on a
            // signature match, so the missing provider's row is
            // retried on the next reload instead of never
            // appearing until a force reload or restart.
            if (allEntriesResolved) {
                loadedCustomSignature = customSignature(list)
            }
            _page.postValue(Resource.Success(expandable))

            // Phase 8.47: the hero used to wait for up to 3
            // full page loads before posting anything — on
            // slow providers it sat empty for ages (his
            // report). Rows and the hero strip post NOW; the
            // enrichment runs in its own job and re-posts the
            // preview after EACH successful load, so the hero
            // appears with the first backdrop that is ready.
            val flat = built.values.flatMap { it.list.list }
                .distinctBy { it.url }
                .filterNot {
                    FamilyMode.isEnabled() && FamilyMode.isAdultItem(it)
                }
                .shuffled()
            currentShuffledList = flat
            _randomItems.postValue(flat)
            _preview.postValue(Resource.Loading())
            ioSafe {
                previewResponses.clear()
                previewResponsesAdded.clear()
                // Phase 8.56: these detail loads used to run one
                // after another — with a slow provider first, the
                // hero sat empty for the sum of every load (his
                // "hero loads late" report). Fetch the candidates
                // in parallel, take the first three successes.
                val loaded = flat.take(10).amap { sr ->
                    val apiX = getApiFromNameNull(sr.apiName)
                        ?: return@amap null
                    when (val r = APIRepository(apiX).load(sr.url)) {
                        is Resource.Success -> sr.url to r.value
                        else -> null
                    }
                }.filterNotNull()
                for ((url, response) in loaded) {
                    if (previewResponses.size >= 3) break
                    previewResponses.add(response)
                    previewResponsesAdded.add(url)
                }
                if (previewResponses.isEmpty()) {
                    _preview.postValue(
                        Resource.Failure(
                            false, "No homepage responses"
                        )
                    )
                } else {
                    _preview.postValue(
                        Resource.Success(
                            (previewResponsesAdded.size <
                                flat.size) to previewResponses
                        )
                    )
                }
            }
            isCurrentlyLoadingName = null
        }
    }

    fun click(callback: SearchClickCallback) {
        if (callback.action != SEARCH_ACTION_FOCUSED) {
            SearchHelper.handleSearchClickCallback(callback)
        }
    }

    private val _popup = MutableLiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?>(null)
    val popup: LiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?> = _popup

    fun popup(list: ExpandableHomepageList?, deleteCallback: (() -> Unit)? = null) {
        if (list == null)
            _popup.postValue(null)
        else
            _popup.postValue(list to deleteCallback)
    }

    private fun bookmarksUpdated(unused: Boolean) {
        reloadStored()
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        loadAndCancel(DataStoreHelper.currentHomePage, forceReload)
    }

    private fun afterMainPluginsLoaded(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, false)
    }

    private fun reloadHome(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, true)
    }

    private fun reloadAccount(unused: Boolean = false) {
        _currentAccount.postValue(
            getCurrentAccount()
        )
    }

    init {
        MainActivity.bookmarksUpdatedEvent += ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent += ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent += ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent += ::reloadHome
        MainActivity.reloadAccountEvent += ::reloadAccount
    }

    override fun onCleared() {
        MainActivity.bookmarksUpdatedEvent -= ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent -= ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent -= ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent -= ::reloadHome
        MainActivity.reloadAccountEvent -= ::reloadAccount
        super.onCleared()
    }

    fun queryTextSubmit(query: String) {
        QuickSearchFragment.pushSearch(
            query,
            repo?.name?.let { arrayOf(it) })
    }

    fun queryTextChange(newText: String) {
        // do nothing
    }

    fun loadStoredData() {
        val list = EnumSet.noneOf(WatchType::class.java)
        DataStoreHelper.homeBookmarkedList.map { WatchType.fromInternalId(it) }.let {
            list.addAll(it)
        }
        loadStoredData(list)
    }

    fun reloadStored() {
        loadResumeWatching()
        loadStoredData()
    }

    fun click(load: LoadClickCallback) {
        loadResult(load.response.url, load.response.apiName, load.response.name, load.action)
    }

    // only save the key if it is from UI, as we don't want internal functions changing the setting
    fun loadAndCancel(
        preferredApiName: String?,
        forceReload: Boolean = true,
        fromUI: Boolean = false
    ) =
        ioSafe {
            // Phase 8.44: while a custom list is active, every home
            // load routes to it. Leaving custom mode happens only
            // via exitCustomMode() (the provider switcher).
            val activeCustom =
                context?.let { HomeCustomLists.activeList(it) }
            // Phase 8.46: the list was deactivated in Settings —
            // drop the stale custom rows and force a normal load.
            // (No recursive call here: this function has an
            // inferred return type, and a self-call makes the
            // type checker loop — CI caught exactly that.)
            var effectiveForce = forceReload
            if (activeCustom == null && customRoutes.isNotEmpty()) {
                customRoutes.clear()
                loadedCustomSignature = null
                effectiveForce = true
            }
            if (activeCustom != null) {
                if (!forceReload &&
                    loadedCustomSignature ==
                    customSignature(activeCustom) &&
                    page.value is Resource.Success
                ) {
                    return@ioSafe
                }
                loadCustomList(activeCustom)
                reloadAccount()
                return@ioSafe
            }
            //println("trying to load $preferredApiName")
            // Since plugins are loaded in stages this function can get called multiple times.
            // The issue with this is that the homepage may be fetched multiple times while the first request is loading
            // api?.let { expandable[it.name]?.list?.list?.isNotEmpty() } == true
            val currentPage = page.value

            // if we don't need to reload and we have a valid homepage or currently loading the same thing then return
            val currentLoading = isCurrentlyLoadingName
            if (!effectiveForce && (currentPage is Resource.Success && currentPage.value.isNotEmpty() || (currentLoading != null && currentLoading == preferredApiName))) {
                return@ioSafe
            }

            val api = getApiFromNameNull(preferredApiName)
            if (preferredApiName == noneApi.name) {
                // just set to random
                if (fromUI) DataStoreHelper.currentHomePage = noneApi.name
                loadAndCancel(noneApi)
            } else if (preferredApiName == randomApi.name) {
                // randomize the api, if none exist like if not loaded or not installed
                // then use nothing
                val validAPIs = context?.filterProviderByPreferredMedia()
                if (validAPIs.isNullOrEmpty()) {
                    loadAndCancel(noneApi)
                } else {
                    val apiRandom = validAPIs.random()
                    loadAndCancel(apiRandom)
                    if (fromUI) DataStoreHelper.currentHomePage = apiRandom.name
                }
            } else if (api == null) {
                // API is not found aka not loaded or removed, post the loading
                // progress if waiting for plugins, otherwise nothing
                if (PluginManager.loadedOnlinePlugins || PluginManager.isSafeMode()) {
                    loadAndCancel(noneApi)
                } else {
                    _page.postValue(Resource.Loading())
                    if (preferredApiName != null)
                        _apiName.postValue(preferredApiName)
                }
            } else {
                // if the api is found, then set it to it and save key
                if (fromUI) DataStoreHelper.currentHomePage = api.name
                loadAndCancel(api)
            }
            reloadAccount()
        }
}