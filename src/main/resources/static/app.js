(() => {
    'use strict';

    const API = {
        search: (q) => fetch(`/api/search?q=${encodeURIComponent(q)}`).then(handleJson),
        click: (messageId, callbackData) => {
            const body = new URLSearchParams({ messageId, callbackData });
            return fetch('/api/click', { method: 'POST', body }).then(handleJson);
        },
        downloadStatus: (libraryId) => fetch(`/api/download-status?libraryId=${libraryId}`).then(handleJson),
        cancelDownload: (libraryId) => fetch(`/api/download/${libraryId}`, { method: 'DELETE' }).then(handleJson),
        pauseDownload: (libraryId) => fetch(`/api/download/${libraryId}/pause`, { method: 'POST' }).then(handleJson),
        resumeDownload: (libraryId) => fetch(`/api/download/${libraryId}/resume`, { method: 'POST' }).then(handleJson),
        library: () => fetch('/api/library').then(handleJson),
        deleteFromLibrary: (libraryId) => fetch(`/api/library/${libraryId}`, { method: 'DELETE' }).then(handleJson),
        streamUrl: (libraryId) => `/api/stream/${libraryId}`,
        savePosition: (libraryId, seconds) => {
            const body = new URLSearchParams({ seconds: String(seconds) });
            return fetch(`/api/library/${libraryId}/position`, { method: 'POST', body }).then(handleJson);
        },
        storage: () => fetch('/api/storage').then(handleJson),
        subtitleTracks: (libraryId) => fetch(`/api/stream/${libraryId}/subtitles`).then(handleJson),
        subtitleTrackUrl: (libraryId, trackIndex) => `/api/stream/${libraryId}/subtitles/${trackIndex}.vtt`,
        audioTracks: (libraryId) => fetch(`/api/stream/${libraryId}/audio-tracks`).then(handleJson),
        selectAudioTrack: (libraryId, trackIndex) =>
            fetch(`/api/stream/${libraryId}/audio-tracks/${trackIndex}`, { method: 'POST' }).then(handleJson),
        clearAudioTrack: (libraryId) =>
            fetch(`/api/stream/${libraryId}/audio-tracks`, { method: 'DELETE' }).then(handleJson),
        status: () => fetch('/api/status').then(handleJson),
    };

    async function handleJson(res) {
        if (!res.ok) {
            let message = `Request failed (${res.status})`;
            let errorCode = null;
            try {
                const body = await res.json();
                if (body.message) message = body.message;
                if (body.error) errorCode = body.error;
            } catch (_) { /* not JSON */ }
            const err = new Error(message);
            err.code = errorCode;
            throw err;
        }
        if (res.status === 204) return null; // no content (delete/pause/cancel endpoints)
        return res.json();
    }

    // ---------- State ----------
    const state = {
        currentMessageId: null,
        currentButtonRows: [],
        currentFilters: { season: null, language: null },
        confirmedTitle: null,
        mergedButtons: [],       // file buttons collected across all pages loaded so far
        seenCallbacks: new Set(), // dedupe guard for mergedButtons
        backgroundToken: 0,       // bumped on every new search to cancel a stale background load
        backgroundLoading: false,
        lastQuery: null,         // the query behind the currently-shown results, for stale-selection recovery
        libraryItems: [],        // raw, unfiltered list from the last /api/library load
        libraryQuery: '',
        libraryStatusFilter: 'all',
        librarySort: 'newest',
    };

    const MAX_BACKGROUND_PAGES = 25; // safety cap so a search with many pages doesn't run forever

    const RECENT_KEY = 'movierelay_recent_searches';

    // ---------- DOM refs ----------
    const el = (id) => document.getElementById(id);
    const backBtn = el('backBtn');
    const searchForm = el('searchForm');
    const searchInput = el('searchInput');
    const searchSuggestions = el('searchSuggestions');
    const libraryBtn = el('libraryBtn');
    const connectionBanner = el('connectionBanner');
    const connectionBannerText = el('connectionBannerText');
    const homeSection = el('homeSection');
    const homeEmpty = el('homeEmpty');
    const recentSection = el('recentSection');
    const recentChips = el('recentChips');
    const recentEmpty = el('recentEmpty');
    const clearRecentBtn = el('clearRecentBtn');
    const continueSection = el('continueSection');
    const continueRow = el('continueRow');
    const resultsSection = el('resultsSection');
    const resultsHeader = el('resultsHeader');
    const resultsPoster = el('resultsPoster');
    const botTitleBanner = el('botTitleBanner');
    const resultsList = el('resultsList');
    const paginationRow = el('paginationRow');
    const prevPageBtn = el('prevPageBtn');
    const nextPageBtn = el('nextPageBtn');
    const pageIndicator = el('pageIndicator');
    const librarySection = el('librarySection');
    const libraryList = el('libraryList');
    const libraryEmpty = el('libraryEmpty');
    const libraryNoMatches = el('libraryNoMatches');
    const libraryToolbar = el('libraryToolbar');
    const librarySearchInput = el('librarySearchInput');
    const libraryStatusTabs = el('libraryStatusTabs');
    const librarySortBtn = el('librarySortBtn');
    const librarySortMenu = el('librarySortMenu');
    const libraryBulkActions = el('libraryBulkActions');
    const pauseAllBtn = el('pauseAllBtn');
    const resumeAllBtn = el('resumeAllBtn');
    const storageInfo = el('storageInfo');
    const storageBarFill = el('storageBarFill');
    const storageUsedLabel = el('storageUsedLabel');
    const storageFreeLabel = el('storageFreeLabel');
    const loadingOverlay = el('loadingOverlay');
    const loadingText = el('loadingText');
    const toast = el('toast');

    // ---------- View management ----------
    function showView(view) {
        homeSection.classList.toggle('hidden', view !== 'home');
        recentSection.classList.toggle('hidden', view !== 'recent');
        resultsSection.classList.toggle('hidden', view !== 'results');
        librarySection.classList.toggle('hidden', view !== 'library');
        backBtn.classList.toggle('hidden', view === 'home');
    }

    function showLoading(text) {
        loadingText.textContent = text || 'Loading...';
        loadingOverlay.classList.remove('hidden');
    }
    function hideLoading() {
        loadingOverlay.classList.add('hidden');
    }

    function showToast(message, isError) {
        toast.textContent = message;
        toast.classList.toggle('error', !!isError);
        toast.classList.remove('hidden');
        clearTimeout(showToast._t);
        showToast._t = setTimeout(() => toast.classList.add('hidden'), 3500);
    }

    // ---------- Recent searches ----------
    function getRecent() {
        try {
            return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
        } catch (_) { return []; }
    }
    function addRecent(query) {
        const list = getRecent().filter((q) => q.toLowerCase() !== query.toLowerCase());
        list.unshift(query);
        localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, 10)));
        renderRecent();
    }
    function clearRecent() {
        localStorage.removeItem(RECENT_KEY);
        renderRecent();
    }
    function removeRecent(query) {
        const list = getRecent().filter((q) => q !== query);
        localStorage.setItem(RECENT_KEY, JSON.stringify(list));
        renderRecent();
    }
    function renderRecent() {
        const list = getRecent();
        recentChips.innerHTML = '';
        recentEmpty.classList.toggle('hidden', list.length > 0);
        list.forEach((q) => {
            const chip = document.createElement('div');
            chip.className = 'chip chip-removable';

            const label = document.createElement('button');
            label.className = 'chip-label';
            label.textContent = q;
            label.addEventListener('click', () => runSearch(q));

            const removeBtn = document.createElement('button');
            removeBtn.className = 'chip-remove';
            removeBtn.setAttribute('aria-label', `Remove "${q}"`);
            removeBtn.innerHTML = '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
            removeBtn.addEventListener('click', (evt) => {
                evt.stopPropagation();
                removeRecent(q);
            });

            chip.appendChild(label);
            chip.appendChild(removeBtn);
            recentChips.appendChild(chip);
        });
    }

    // ---------- Continue Watching (home screen) ----------
    async function loadContinueWatching() {
        try {
            const items = await API.library();
            renderContinueWatching(items);
        } catch (e) {
            // Non-critical - just leave the row hidden if the library can't be loaded.
            continueSection.classList.add('hidden');
            homeEmpty.classList.remove('hidden');
        }
    }

    function renderContinueWatching(items) {
        items.sort((a, b) => new Date(b.downloadedAt) - new Date(a.downloadedAt));
        continueRow.innerHTML = '';
        continueSection.classList.toggle('hidden', items.length === 0);
        homeEmpty.classList.toggle('hidden', items.length > 0);

        items.forEach((item) => {
            const card = document.createElement('button');
            card.className = 'poster-card';

            const imgWrap = document.createElement('div');
            imgWrap.className = 'poster-card-img-wrap';
            imgWrap.appendChild(buildPosterOrIcon(item.posterUrl));

            if (item.paused) {
                const badge = document.createElement('span');
                badge.className = 'poster-card-badge';
                badge.textContent = 'Paused';
                imgWrap.appendChild(badge);
            } else if (!item.completed) {
                const badge = document.createElement('span');
                badge.className = 'poster-card-badge';
                badge.textContent = 'Downloading';
                imgWrap.appendChild(badge);

                const progress = document.createElement('div');
                progress.className = 'poster-card-progress';
                const fill = document.createElement('div');
                fill.className = 'poster-card-progress-fill';
                fill.style.width = '0%';
                progress.appendChild(fill);
                imgWrap.appendChild(progress);
            }

            const title = document.createElement('div');
            title.className = 'poster-card-title';
            title.textContent = item.cleanTitle || item.displayName;

            card.appendChild(imgWrap);
            card.appendChild(title);

            card.addEventListener('click', () => {
                if (item.paused) {
                    showToast('This download is paused. Resume it from the Library to keep downloading.');
                    return;
                }
                const label = item.cleanTitle || item.displayName;
                openPlayerForFile({ libraryId: item.primaryLibraryId, fileName: item.displayName }, label, item.lastPositionSeconds);
            });

            continueRow.appendChild(card);
        });
    }

    // ---------- Search ----------
    async function runSearch(query) {
        query = query.trim();
        if (!query) return;
        searchInput.value = query;
        searchSuggestions.classList.add('hidden');
        showView('results');
        showLoading('Searching...');
        try {
            const reply = await API.search(query);
            state.lastQuery = query;
            addRecent(query);
            renderBotReply(reply, true);
        } catch (e) {
            showToast(e.message, true);
            showView('home');
        } finally {
            hideLoading();
        }
    }

    // Codes worth a single automatic retry - the bot's single reply-slot can be transiently
    // busy (e.g. background season/episode pagination racing a user's own click), and a short
    // retry usually succeeds without bothering the user with an error they'd just retry anyway.
    const RETRYABLE_ERROR_CODES = new Set(['bot_timeout', 'bot_busy']);

    // originalBtn (optional) is the file button as originally rendered - kept around so that if
    // its callbackData turns out to be stale (see recoverFromStaleSelection), we can find the
    // same file again in a freshly re-run search rather than just failing.
    async function clickButton(messageId, callbackDataB64, label, originalBtn) {
        showLoading(label ? `Opening "${label}"...` : 'Please wait...');
        try {
            let reply;
            try {
                reply = await API.click(messageId, callbackDataB64);
            } catch (e) {
                if (e.code === 'stale_selection' && originalBtn && state.lastQuery) {
                    reply = await recoverFromStaleSelection(originalBtn);
                } else if (RETRYABLE_ERROR_CODES.has(e.code)) {
                    await sleep(1500);
                    reply = await API.click(messageId, callbackDataB64);
                } else {
                    throw e;
                }
            }
            if (reply.file) {
                hideLoading();
                openPlayerForFile(reply.file, label || reply.text);
            } else {
                renderBotReply(reply);
            }
        } catch (e) {
            showToast(e.message, true);
        } finally {
            hideLoading();
        }
    }

    // A file selection can go stale even long after it was first shown (the bot's message/
    // callback data appears to expire on its own, independent of our own background paging -
    // see the "DATA_INVALID" investigation). Recovering means re-running the same search from
    // scratch and finding the equivalent file in the fresh results by matching its parsed
    // season/episode/resolution/language/size (a stable fingerprint for "the same file", even
    // though its callbackData and the message it lives on are now completely different).
    async function recoverFromStaleSelection(originalBtn) {
        showLoading('Refreshing results...');
        let reply = await API.search(state.lastQuery);
        let match = (reply.buttonRows || []).flat().find((b) => isSameFile(b, originalBtn));

        // The original file may have been on a later page (e.g. season 4 of a 6-season show) -
        // walk forward the same way the background loader does until it turns up or pages run
        // out, so recovery works for deep matches too, not just whatever's on page 1.
        let nextBtn = match ? null : findNavButton(reply.buttonRows || [], 'next');
        let pagesWalked = 1;
        while (!match && nextBtn && pagesWalked < MAX_BACKGROUND_PAGES) {
            reply = await API.click(reply.messageId, nextBtn.callbackData);
            match = (reply.buttonRows || []).flat().find((b) => isSameFile(b, originalBtn));
            nextBtn = match ? null : findNavButton(reply.buttonRows || [], 'next');
            pagesWalked += 1;
        }

        if (!match) {
            // Couldn't find the same file anywhere - surface the refreshed list so the user can
            // pick again, rather than silently failing with no path forward.
            renderBotReply(reply, true);
            throw new Error('That selection is no longer available. The results have been refreshed - please pick again.');
        }

        showLoading('Opening...');
        return API.click(reply.messageId, match.callbackData);
    }

    function isSameFile(a, b) {
        const pa = a.parsed || {};
        const pb = b.parsed || {};
        return pa.season === pb.season
            && pa.episode === pb.episode
            && pa.resolution === pb.resolution
            && pa.language === pb.language
            && pa.sizeText === pb.sizeText
            && cleanTitle(a.text) === cleanTitle(b.text);
    }

    // ---------- Rendering results ----------
    function renderBotReply(reply, isFreshSearch) {
        state.currentMessageId = reply.messageId;
        state.currentButtonRows = reply.buttonRows || [];
        state.currentFilters = { season: null, language: null };

        const newTitle = extractConfirmedTitle(reply.text);
        if (newTitle) state.confirmedTitle = newTitle;

        botTitleBanner.textContent = (reply.text || '').replace(/[⭕️❗👋🏻‎‌🎬]/g, '').trim();
        resultsHeader.classList.toggle('hidden', !reply.text);

        if (reply.posterUrl) {
            resultsPoster.src = reply.posterUrl;
            resultsPoster.classList.remove('hidden');
            resultsPoster.onerror = () => resultsPoster.classList.add('hidden');
        } else {
            resultsPoster.removeAttribute('src');
            resultsPoster.classList.add('hidden');
        }

        if (isFreshSearch) {
            state.mergedButtons = [];
            state.seenCallbacks = new Set();
            mergeButtonsFromRows(state.currentButtonRows);
            maybeStartBackgroundLoad(reply);
        }

        renderResultsList();
        renderPagination();
        showView('results');
    }

    function extractConfirmedTitle(text) {
        const match = /Title\s*:\s*(.+)/i.exec(text || '');
        return match ? match[1].trim() : null;
    }

    function mergeButtonsFromRows(buttonRows) {
        for (const row of buttonRows) {
            for (const btn of row) {
                if (!btn.callbackData || !btn.parsed?.sizeText) continue;
                if (state.seenCallbacks.has(btn.callbackData)) continue;
                if (!matchesConfirmedTitle(btn.text, btn.parsed.sizeText)) continue;
                state.seenCallbacks.add(btn.callbackData);
                state.mergedButtons.push(btn);
            }
        }
    }

    function findNavButton(buttonRows, kind) {
        const re = kind === 'next' ? /next/i : /previous/i;
        for (const row of buttonRows) {
            for (const btn of row) {
                if (re.test((btn.text || '').trim())) return btn;
            }
        }
        return null;
    }

    /**
     * Only worth doing for series (season/episode data present) - a plain movie search has
     * nothing to merge across pages. Walks "Next" pages in the background, in the same
     * message thread the user is looking at, merging their file buttons into
     * state.mergedButtons and re-rendering after each page so results reorder into place as
     * they arrive. Bails out if the user starts a different search (token mismatch), hits the
     * page cap, or runs out of "Next" buttons.
     */
    async function maybeStartBackgroundLoad(firstPageReply) {
        const hasSeasons = (firstPageReply.buttonRows || [])
            .flat()
            .some((b) => b.parsed?.season != null);
        if (!hasSeasons) return;

        let nextBtn = findNavButton(firstPageReply.buttonRows, 'next');
        if (!nextBtn) return;

        const myToken = ++state.backgroundToken;
        state.backgroundLoading = true;
        updateBackgroundLoadIndicator();

        let messageId = firstPageReply.messageId;
        let pagesLoaded = 1;

        while (nextBtn && pagesLoaded < MAX_BACKGROUND_PAGES) {
            if (state.backgroundToken !== myToken) return; // a new search started - abandon

            try {
                const reply = await API.click(messageId, nextBtn.callbackData);
                if (state.backgroundToken !== myToken) return;

                mergeButtonsFromRows(reply.buttonRows || []);
                pagesLoaded += 1;
                renderResultsList(); // re-render the currently visible page with the fuller merged pool
                nextBtn = findNavButton(reply.buttonRows || [], 'next');
            } catch (e) {
                // Most likely a 409 bot_busy because the user is actively clicking something -
                // back off briefly and retry the same page rather than giving up.
                await sleep(1500);
            }
        }

        if (state.backgroundToken === myToken) {
            state.backgroundLoading = false;
            updateBackgroundLoadIndicator();
        }
    }

    function sleep(ms) {
        return new Promise((resolve) => setTimeout(resolve, ms));
    }

    function updateBackgroundLoadIndicator() {
        const indicator = el('backgroundLoadIndicator');
        indicator.classList.toggle('hidden', !state.backgroundLoading);

        // buildResultItem already sets the disabled state for buttons built while loading is
        // active, but the final "loading finished" transition doesn't re-render the list - so
        // sweep the currently-rendered buttons here to re-enable them once it's done.
        resultsList.querySelectorAll('.result-item').forEach((btn) => {
            btn.disabled = state.backgroundLoading;
        });
    }

    function normalizeForMatch(text) {
        return (text || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
    }

    function matchesConfirmedTitle(buttonText, sizeText) {
        if (!state.confirmedTitle) return true;
        const needle = normalizeForMatch(state.confirmedTitle);
        if (!needle) return true;

        // Real results are formatted as "<size> ● <Title> ...more info...", so the
        // title should appear right at the start of the label once the leading size/bullet
        // is stripped off. A title that only shows up later in the string (e.g. a different
        // show whose episode happens to be titled after ours) is not a real match, even
        // though the bot's fuzzy search still returned it.
        let rest = buttonText || '';
        if (sizeText) {
            const idx = rest.indexOf(sizeText);
            if (idx !== -1) rest = rest.slice(idx + sizeText.length);
        }
        rest = rest.replace(/^[^a-zA-Z0-9]+/, '');
        const haystack = normalizeForMatch(rest);
        return haystack.startsWith(needle);
    }

    function flattenFileButtons() {
        // For series, state.mergedButtons accumulates file buttons across every page the
        // background loader has fetched so far (starting with just page 1's), so episodes
        // reorder into their correct place as more pages arrive - see maybeStartBackgroundLoad.
        // For movies (no background load ever started) this is simply the current page.
        if (state.mergedButtons.length > 0) {
            return state.mergedButtons;
        }

        const buttons = [];
        for (const row of state.currentButtonRows) {
            for (const btn of row) {
                if (!btn.callbackData) continue;
                // A genuine file button always carries a parsed size (e.g. "536.33 MB") -
                // everything else (the title-refresh button, Next/Previous/Pages controls)
                // does not, so this is a more reliable filter than matching on button text.
                if (!btn.parsed?.sizeText) continue;
                if (!matchesConfirmedTitle(btn.text, btn.parsed.sizeText)) continue;
                buttons.push(btn);
            }
        }
        return buttons;
    }

    function renderResultsList() {
        const fileButtons = flattenFileButtons();
        resultsList.innerHTML = '';

        if (fileButtons.length === 0) {
            resultsList.innerHTML = '<p class="empty-hint">No file options on this page.</p>';
            return;
        }

        const hasSeasons = fileButtons.some((b) => b.parsed?.season != null);
        const languages = [...new Set(fileButtons.map((b) => b.parsed?.language).filter(Boolean))];

        const filterBar = buildFilterBar(fileButtons, hasSeasons, languages);
        if (filterBar) resultsList.appendChild(filterBar);

        const filtered = fileButtons.filter((b) => {
            if (state.currentFilters.season != null && b.parsed?.season !== state.currentFilters.season) return false;
            if (state.currentFilters.language && b.parsed?.language !== state.currentFilters.language) return false;
            return true;
        });

        if (hasSeasons) {
            renderGroupedBySeasonEpisode(filtered);
        } else {
            renderFlatList(filtered);
        }
    }

    function buildFilterBar(fileButtons, hasSeasons, languages) {
        if (!hasSeasons && languages.length <= 1) return null;

        const wrap = document.createElement('div');

        if (hasSeasons) {
            const seasons = [...new Set(fileButtons.map((b) => b.parsed?.season).filter((s) => s != null))].sort((a, b) => a - b);
            if (seasons.length > 1) {
                const tabs = document.createElement('div');
                tabs.className = 'filter-tabs';
                const allTab = makeTab('All seasons', state.currentFilters.season == null, () => {
                    state.currentFilters.season = null;
                    renderResultsList();
                });
                tabs.appendChild(allTab);
                seasons.forEach((s) => {
                    tabs.appendChild(makeTab(`S${String(s).padStart(2, '0')}`, state.currentFilters.season === s, () => {
                        state.currentFilters.season = s;
                        renderResultsList();
                    }));
                });
                wrap.appendChild(tabs);
            }
        }

        if (languages.length > 1) {
            const tabs = document.createElement('div');
            tabs.className = 'filter-tabs';
            tabs.appendChild(makeTab('All languages', !state.currentFilters.language, () => {
                state.currentFilters.language = null;
                renderResultsList();
            }));
            languages.forEach((lang) => {
                tabs.appendChild(makeTab(lang, state.currentFilters.language === lang, () => {
                    state.currentFilters.language = lang;
                    renderResultsList();
                }));
            });
            wrap.appendChild(tabs);
        }

        return wrap;
    }

    function makeTab(label, active, onClick) {
        const btn = document.createElement('button');
        btn.className = 'filter-tab' + (active ? ' active' : '');
        btn.textContent = label;
        btn.addEventListener('click', onClick);
        return btn;
    }

    function renderGroupedBySeasonEpisode(buttons) {
        const bySeason = new Map();
        for (const b of buttons) {
            const s = b.parsed?.season ?? 0;
            if (!bySeason.has(s)) bySeason.set(s, []);
            bySeason.get(s).push(b);
        }
        const seasons = [...bySeason.keys()].sort((a, b) => a - b);
        for (const s of seasons) {
            const header = document.createElement('div');
            header.className = 'group-header';
            header.textContent = s > 0 ? `Season ${s}` : 'Other files';
            resultsList.appendChild(header);

            const group = bySeason.get(s).sort((a, b) => (a.parsed?.episode ?? 0) - (b.parsed?.episode ?? 0));
            const listWrap = document.createElement('div');
            listWrap.className = 'result-list';
            group.forEach((btn) => listWrap.appendChild(buildResultItem(btn)));
            resultsList.appendChild(listWrap);
        }
    }

    function renderFlatList(buttons) {
        const listWrap = document.createElement('div');
        listWrap.className = 'result-list';
        buttons.forEach((btn) => listWrap.appendChild(buildResultItem(btn)));
        resultsList.appendChild(listWrap);
    }

    function buildResultItem(btn) {
        const item = document.createElement('button');
        item.className = 'result-item';
        item.disabled = state.backgroundLoading;

        const icon = document.createElement('div');
        icon.className = 'result-icon';
        icon.innerHTML = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>';

        const body = document.createElement('div');
        body.className = 'result-body';

        const title = document.createElement('div');
        title.className = 'result-title';
        title.textContent = cleanTitle(btn.text);

        const tags = document.createElement('div');
        tags.className = 'result-tags';
        const p = btn.parsed || {};
        if (p.episode != null) tags.appendChild(makeTag(`E${String(p.episode).padStart(2, '0')}`, 'tag-episode'));
        if (p.resolution) tags.appendChild(makeTag(p.resolution));
        if (p.language) tags.appendChild(makeTag(p.language));
        if (p.sizeText) tags.appendChild(makeTag(p.sizeText, 'tag-size'));

        body.appendChild(title);
        body.appendChild(tags);
        item.appendChild(icon);
        item.appendChild(body);

        item.addEventListener('click', () => {
            if (state.backgroundLoading) return; // still merging pages - avoid clicking a button that may not be stable yet
            clickButton(state.currentMessageId, btn.callbackData, cleanTitle(btn.text), btn);
        });

        return item;
    }

    function makeTag(text, extraClass) {
        const span = document.createElement('span');
        span.className = 'tag' + (extraClass ? ' ' + extraClass : '');
        span.textContent = text;
        return span;
    }

    function cleanTitle(text) {
        return (text || '').replace(/^[\d.]+\s?(GB|MB)\s?●\s?/i, '').trim();
    }

    function buildPosterOrIcon(posterUrl) {
        if (posterUrl) {
            const img = document.createElement('img');
            img.className = 'result-poster';
            img.src = posterUrl;
            img.alt = '';
            img.loading = 'lazy';
            // If the poster URL ever fails to load, fall back to the plain icon rather than
            // showing a broken-image glyph.
            img.addEventListener('error', () => {
                img.replaceWith(buildPlaceholderIcon());
            }, { once: true });
            return img;
        }
        return buildPlaceholderIcon();
    }

    function buildPlaceholderIcon() {
        const icon = document.createElement('div');
        icon.className = 'result-icon';
        icon.innerHTML = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>';
        return icon;
    }

    function renderPagination() {
        if (state.mergedButtons.length > 0) {
            // Once background merging has produced a unified, sorted pool, per-page
            // Next/Previous controls no longer correspond to what's on screen.
            paginationRow.classList.add('hidden');
            return;
        }

        let prevBtn = null, nextBtn = null, indicator = null;
        for (const row of state.currentButtonRows) {
            for (const btn of row) {
                const t = (btn.text || '').trim();
                if (/previous/i.test(t)) prevBtn = btn;
                else if (/next/i.test(t)) nextBtn = btn;
                else if (/^\d+\s*\/\s*\d+$/.test(t)) indicator = t;
            }
        }

        const hasPagination = prevBtn || nextBtn;
        paginationRow.classList.toggle('hidden', !hasPagination);
        prevPageBtn.classList.toggle('hidden', !prevBtn);
        nextPageBtn.classList.toggle('hidden', !nextBtn);
        pageIndicator.textContent = indicator || '';

        prevPageBtn.onclick = prevBtn ? () => clickButton(state.currentMessageId, prevBtn.callbackData) : null;
        nextPageBtn.onclick = nextBtn ? () => clickButton(state.currentMessageId, nextBtn.callbackData) : null;
    }

    // ---------- Library ----------
    // showSkeleton: true only for the first load when opening the tab - a full-screen blocking
    // spinner every time felt heavier than needed for what's usually a fast local reload, so
    // this shows lightweight placeholder rows in place instead. Background refreshes (after
    // pause/resume/delete, or a download completing) skip this and just update quietly, since
    // flashing skeleton rows on every such action would be more distracting than helpful.
    async function loadLibrary(showSkeleton) {
        showView('library');
        if (showSkeleton) renderLibrarySkeleton();
        loadStorageInfo(); // independent of the main library list - failure here shouldn't block it
        try {
            const items = await API.library();
            state.libraryItems = items;
            libraryToolbar.classList.toggle('hidden', items.length === 0);
            updateBulkActionVisibility(items);
            applyLibraryFiltersAndRender();
        } catch (e) {
            showToast(e.message, true);
        }
    }

    function renderLibrarySkeleton() {
        libraryEmpty.classList.add('hidden');
        libraryNoMatches.classList.add('hidden');
        libraryList.innerHTML = '';
        for (let i = 0; i < 4; i++) {
            const row = document.createElement('div');
            row.className = 'library-skeleton-row';
            row.innerHTML = '<div class="skeleton-block skeleton-poster"></div>' +
                '<div class="skeleton-lines"><div class="skeleton-block skeleton-line-title"></div><div class="skeleton-block skeleton-line-sub"></div></div>';
            libraryList.appendChild(row);
        }
    }

    const LIBRARY_SORT_OPTIONS = [
        { key: 'newest', label: 'Newest first', compare: (a, b) => new Date(b.downloadedAt) - new Date(a.downloadedAt) },
        { key: 'oldest', label: 'Oldest first', compare: (a, b) => new Date(a.downloadedAt) - new Date(b.downloadedAt) },
        { key: 'name', label: 'Name (A-Z)', compare: (a, b) => (a.cleanTitle || a.displayName).localeCompare(b.cleanTitle || b.displayName) },
        { key: 'largest', label: 'Largest first', compare: (a, b) => b.sizeBytes - a.sizeBytes },
        { key: 'smallest', label: 'Smallest first', compare: (a, b) => a.sizeBytes - b.sizeBytes },
    ];

    // Used for the toolbar's status filter tabs only - paused items are grouped under
    // "Downloading" there (they're still in-progress downloads, just temporarily stopped), not
    // split into their own tab. The per-row status label/menu still distinguish paused from
    // actively downloading (see statusLabel/buildLibraryMenu) - that's a finer distinction than
    // the filter needs.
    function itemStatus(item) {
        return item.completed ? 'completed' : 'downloading';
    }

    function applyLibraryFiltersAndRender() {
        const query = state.libraryQuery.trim().toLowerCase();
        let items = state.libraryItems.filter((item) => {
            if (state.libraryStatusFilter !== 'all' && itemStatus(item) !== state.libraryStatusFilter) return false;
            if (query && !(item.cleanTitle || item.displayName).toLowerCase().includes(query)) return false;
            return true;
        });

        const sortOption = LIBRARY_SORT_OPTIONS.find((o) => o.key === state.librarySort) || LIBRARY_SORT_OPTIONS[0];
        items = items.slice().sort(sortOption.compare);

        renderLibrary(items);
        libraryEmpty.classList.toggle('hidden', state.libraryItems.length > 0);
        libraryNoMatches.classList.toggle('hidden', items.length > 0 || state.libraryItems.length === 0);
    }

    // Bulk actions always act on the FULL library (state.libraryItems), not whatever the
    // current search/filter happens to be showing - "Pause all" should mean all, not "all
    // currently visible", which would be a confusing, easy-to-misuse distinction.
    function updateBulkActionVisibility(items) {
        const hasDownloading = items.some((item) => !item.completed && !item.paused);
        const hasPaused = items.some((item) => item.paused);
        pauseAllBtn.classList.toggle('hidden', !hasDownloading);
        resumeAllBtn.classList.toggle('hidden', !hasPaused);
        libraryBulkActions.classList.toggle('hidden', !hasDownloading && !hasPaused);
    }

    async function pauseAllDownloads() {
        const targets = state.libraryItems.filter((item) => !item.completed && !item.paused);
        if (!targets.length) return;
        pauseAllBtn.disabled = true;
        try {
            const results = await Promise.allSettled(targets.map((item) => API.pauseDownload(item.primaryLibraryId)));
            const failed = results.filter((r) => r.status === 'rejected').length;
            if (failed > 0) {
                showToast(`Paused ${targets.length - failed} of ${targets.length} downloads (${failed} failed).`, true);
            }
        } finally {
            pauseAllBtn.disabled = false;
            loadLibrary();
        }
    }

    async function resumeAllDownloads() {
        const targets = state.libraryItems.filter((item) => item.paused);
        if (!targets.length) return;
        resumeAllBtn.disabled = true;
        try {
            const results = await Promise.allSettled(targets.map((item) => API.resumeDownload(item.primaryLibraryId)));
            const failed = results.filter((r) => r.status === 'rejected').length;
            if (failed > 0) {
                showToast(`Resumed ${targets.length - failed} of ${targets.length} downloads (${failed} failed).`, true);
            }
        } finally {
            resumeAllBtn.disabled = false;
            loadLibrary();
        }
    }

    pauseAllBtn.addEventListener('click', pauseAllDownloads);
    resumeAllBtn.addEventListener('click', resumeAllDownloads);

    let librarySearchDebounce = null;
    librarySearchInput.addEventListener('input', () => {
        clearTimeout(librarySearchDebounce);
        librarySearchDebounce = setTimeout(() => {
            state.libraryQuery = librarySearchInput.value;
            applyLibraryFiltersAndRender();
        }, 200);
    });

    libraryStatusTabs.querySelectorAll('.filter-tab').forEach((tab) => {
        tab.addEventListener('click', () => {
            state.libraryStatusFilter = tab.dataset.filter;
            libraryStatusTabs.querySelectorAll('.filter-tab').forEach((t) => t.classList.toggle('active', t === tab));
            applyLibraryFiltersAndRender();
        });
    });

    LIBRARY_SORT_OPTIONS.forEach((option) => {
        const btn = document.createElement('button');
        btn.className = 'dropdown-item';
        btn.textContent = option.label;
        btn.addEventListener('click', () => {
            state.librarySort = option.key;
            librarySortBtn.textContent = `Sort: ${option.label} ▾`;
            librarySortMenu.classList.add('hidden');
            applyLibraryFiltersAndRender();
        });
        librarySortMenu.appendChild(btn);
    });

    librarySortBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        document.querySelectorAll('.dropdown-menu').forEach((m) => {
            if (m !== librarySortMenu) m.classList.add('hidden');
        });
        librarySortMenu.classList.toggle('hidden');
    });
    document.addEventListener('click', () => librarySortMenu.classList.add('hidden'));

    async function loadStorageInfo() {
        try {
            const info = await API.storage();
            renderStorageInfo(info);
        } catch (e) {
            // Non-critical - just hide the bar if storage stats can't be loaded.
            storageInfo.classList.add('hidden');
        }
    }

    function renderStorageInfo(info) {
        if (!info.diskTotalBytes) {
            storageInfo.classList.add('hidden');
            return;
        }
        storageInfo.classList.remove('hidden');
        const percentUsed = Math.min(100, (info.libraryUsedBytes / info.diskTotalBytes) * 100);
        storageBarFill.style.width = percentUsed + '%';
        storageUsedLabel.textContent = `${formatBytes(info.libraryUsedBytes)} used by library`;
        storageFreeLabel.textContent = `${formatBytes(info.diskFreeBytes)} free`;
    }

    function renderLibrary(items) {
        libraryList.innerHTML = '';
        // Empty-state visibility (nothing downloaded at all vs. no matches for the current
        // search/filter) is decided by the caller, which knows the pre-filter count too.

        items.forEach((item) => {
            const row = document.createElement('div');
            row.className = 'result-item library-item';
            row.style.cursor = 'pointer';

            const icon = buildPosterOrIcon(item.posterUrl);

            const body = document.createElement('div');
            body.className = 'result-body';

            const title = document.createElement('div');
            title.className = 'result-title';
            title.textContent = item.cleanTitle || item.displayName;
            title.title = item.displayName;

            const tags = document.createElement('div');
            tags.className = 'result-tags';
            tags.appendChild(makeTag(formatBytes(item.sizeBytes), 'tag-size'));
            tags.appendChild(makeTag(statusLabel(item)));

            body.appendChild(title);
            body.appendChild(tags);

            if (!item.completed && !item.paused) {
                const progress = document.createElement('div');
                progress.className = 'result-progress';
                const fill = document.createElement('div');
                fill.className = 'result-progress-fill';
                fill.style.width = '0%';
                progress.appendChild(fill);
                body.appendChild(progress);

                const stats = document.createElement('div');
                stats.className = 'download-stats';
                stats.textContent = 'Starting...';
                body.appendChild(stats);

                pollLibraryProgress(item.primaryLibraryId, item.sizeBytes, fill, tags, stats);
            }

            const menuBtn = buildLibraryMenu(item);

            row.appendChild(icon);
            row.appendChild(body);
            row.appendChild(menuBtn);

            row.addEventListener('click', () => {
                if (item.paused) {
                    showToast('This download is paused. Resume it from the menu to keep downloading.');
                    return;
                }
                // Completed or still downloading - either way the stream endpoint can serve
                // it (it waits for/prioritizes whatever bytes the player actually needs), so
                // there's no need to block opening the player until a download finishes.
                const label = item.cleanTitle || item.displayName;
                openPlayerForFile({ libraryId: item.primaryLibraryId, fileName: item.displayName }, label, item.lastPositionSeconds);
            });

            libraryList.appendChild(row);
        });
    }

    function statusLabel(item) {
        if (item.completed) return 'Ready';
        if (item.paused) return 'Paused';
        return 'Downloading...';
    }

    function buildLibraryMenu(item) {
        const wrap = document.createElement('div');
        wrap.className = 'menu-wrap';

        const menuBtn = document.createElement('button');
        menuBtn.className = 'delete-btn';
        menuBtn.setAttribute('aria-label', 'Options');
        menuBtn.innerHTML = '<svg viewBox="0 0 24 24"><circle cx="12" cy="5" r="1.8" fill="currentColor"/><circle cx="12" cy="12" r="1.8" fill="currentColor"/><circle cx="12" cy="19" r="1.8" fill="currentColor"/></svg>';

        const menu = document.createElement('div');
        menu.className = 'dropdown-menu hidden';

        const actions = [];
        if (item.completed) {
            actions.push(['Delete', () => runLibraryAction(() => API.deleteFromLibrary(item.primaryLibraryId))]);
        } else if (item.paused) {
            actions.push(['Resume', () => runLibraryAction(() => API.resumeDownload(item.primaryLibraryId))]);
            actions.push(['Cancel', () => runLibraryAction(() => API.cancelDownload(item.primaryLibraryId))]);
        } else {
            actions.push(['Pause', () => runLibraryAction(() => API.pauseDownload(item.primaryLibraryId))]);
            actions.push(['Cancel', () => runLibraryAction(() => API.cancelDownload(item.primaryLibraryId))]);
        }

        actions.forEach(([label, handler]) => {
            const btn = document.createElement('button');
            btn.className = 'dropdown-item';
            btn.textContent = label;
            btn.addEventListener('click', async (evt) => {
                evt.stopPropagation();
                menu.classList.add('hidden');
                await handler();
            });
            menu.appendChild(btn);
        });

        menuBtn.addEventListener('click', (evt) => {
            evt.stopPropagation();
            document.querySelectorAll('.dropdown-menu').forEach((m) => {
                if (m !== menu) m.classList.add('hidden');
            });
            menu.classList.toggle('hidden');
        });

        document.addEventListener('click', () => menu.classList.add('hidden'));

        wrap.appendChild(menuBtn);
        wrap.appendChild(menu);
        return wrap;
    }

    async function runLibraryAction(action) {
        try {
            await action();
            // Always reload from the server rather than just removing the DOM row - state
            // .libraryItems (the source of truth for search/filter/sort) needs to stay in sync
            // too, or a deleted/cancelled item could reappear after the next filter change.
            loadLibrary();
        } catch (e) {
            showToast(e.message, true);
        }
    }

    function pollLibraryProgress(libraryId, totalBytes, fillEl, tagsEl, statsEl) {
        let lastBytes = null;
        let lastTime = null;
        // Smoothed speed (bytes/sec) rather than the raw instantaneous delta, so the
        // displayed speed/ETA don't jump around wildly between polls.
        let smoothedSpeed = null;

        const interval = setInterval(async () => {
            try {
                const status = await API.downloadStatus(libraryId);
                fillEl.style.width = status.percentComplete + '%';

                const now = Date.now();
                if (lastBytes !== null && lastTime !== null) {
                    const deltaBytes = status.downloadedBytes - lastBytes;
                    const deltaSeconds = (now - lastTime) / 1000;
                    if (deltaSeconds > 0) {
                        const instantSpeed = Math.max(0, deltaBytes) / deltaSeconds;
                        smoothedSpeed = smoothedSpeed === null
                            ? instantSpeed
                            : smoothedSpeed * 0.7 + instantSpeed * 0.3;
                    }
                }
                lastBytes = status.downloadedBytes;
                lastTime = now;

                statsEl.textContent = formatDownloadStats(status.downloadedBytes, totalBytes, smoothedSpeed);

                if (status.completed || status.paused) {
                    clearInterval(interval);
                    loadLibrary();
                }
            } catch (e) {
                // The backend no longer knows about this download (e.g. its file went missing
                // and the startup health check flagged it) - stop polling and make that clear
                // rather than leaving "Downloading..." showing forever with no progress.
                clearInterval(interval);
                fillEl.parentElement.classList.add('hidden');
                statsEl.classList.add('hidden');
                const statusTag = tagsEl.querySelector('.tag:last-child');
                if (statusTag) statusTag.textContent = 'Unavailable';
            }
        }, 2000);
    }

    function formatDownloadStats(downloadedBytes, totalBytes, bytesPerSecond) {
        const sizePart = `${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}`;
        if (!bytesPerSecond || bytesPerSecond <= 0) {
            return sizePart;
        }
        const speedPart = `${formatBytes(bytesPerSecond)}/s`;
        const remainingBytes = Math.max(0, totalBytes - downloadedBytes);
        const etaSeconds = remainingBytes / bytesPerSecond;
        return `${sizePart} • ${speedPart} • ${formatEta(etaSeconds)} left`;
    }

    function formatEta(seconds) {
        if (!isFinite(seconds) || seconds < 0) return '--';
        if (seconds < 60) return Math.ceil(seconds) + 's';
        const minutes = Math.floor(seconds / 60);
        if (minutes < 60) return minutes + 'm ' + Math.round(seconds % 60) + 's';
        const hours = Math.floor(minutes / 60);
        return hours + 'h ' + (minutes % 60) + 'm';
    }

    function formatBytes(bytes) {
        if (!bytes) return '0 MB';
        const gb = bytes / (1024 ** 3);
        if (gb >= 1) return gb.toFixed(2) + ' GB';
        const mb = bytes / (1024 ** 2);
        if (mb >= 1) return mb.toFixed(1) + ' MB';
        return Math.max(0, bytes / 1024).toFixed(0) + ' KB';
    }

    // ---------- Player ----------
    const playerDock = el('playerDock');
    const videoEl = el('videoEl');
    const playerTitle = el('playerTitle');
    const closePlayerBtn = el('closePlayerBtn');
    const subtitleMenuWrap = el('subtitleMenuWrap');
    const subtitleBtn = el('subtitleBtn');
    const subtitleMenu = el('subtitleMenu');
    const audioMenuWrap = el('audioMenuWrap');
    const audioBtn = el('audioBtn');
    const audioMenu = el('audioMenu');
    const speedBtn = el('speedBtn');
    const speedMenu = el('speedMenu');
    const centerPlayPauseBtn = el('centerPlayPauseBtn');
    const centerPlayIcon = el('centerPlayIcon');
    const centerPauseIcon = el('centerPauseIcon');
    const seekBar = el('seekBar');
    const timeCurrent = el('timeCurrent');
    const timeDuration = el('timeDuration');
    const fullscreenBtn = el('fullscreenBtn');
    const playerControls = el('playerControls');
    const bufferingSpinner = el('bufferingSpinner');
    const lockBtn = el('lockBtn');
    const lockOverlay = el('lockOverlay');
    const unlockBtn = el('unlockBtn');
    const gestureLeft = el('gestureLeft');
    const gestureCenter = el('gestureCenter');
    const gestureRight = el('gestureRight');
    const gestureIndicator = el('gestureIndicator');
    const gestureIcon = el('gestureIcon');
    const gestureValue = el('gestureValue');
    const gestureBarFill = el('gestureBarFill');
    const seekIndicatorLeft = el('seekIndicatorLeft');
    const seekIndicatorRight = el('seekIndicatorRight');
    const minimizePlayerBtn = el('minimizePlayerBtn');
    const minimizedOverlay = el('minimizedOverlay');
    const minimizedTitle = el('minimizedTitle');
    const minimizedCloseBtn = el('minimizedCloseBtn');

    let isPlayerMinimized = false;
    let currentPlayingLibraryId = null;
    let positionSaveInterval = null;

    // Resume threshold/margin: don't bother resuming a few seconds in (not worth interrupting
    // autoplay for), and once within a few seconds of the end just start over rather than
    // resuming into the credits.
    const RESUME_MIN_SECONDS = 5;
    const RESUME_END_MARGIN_SECONDS = 15;

    function openPlayerForFile(file, title, resumeSeconds) {
        playerTitle.textContent = title || file.fileName || 'Playing';
        minimizedTitle.textContent = title || file.fileName || 'Playing';
        videoEl.src = API.streamUrl(file.libraryId);
        currentPlayingLibraryId = file.libraryId;
        playerDock.classList.remove('hidden');
        restorePlayer(); // a freshly opened file should always start full-size, not minimized
        setPlaybackSpeed(1); // each newly opened file starts at normal speed

        if (resumeSeconds && resumeSeconds >= RESUME_MIN_SECONDS) {
            const seekToResume = () => {
                if (videoEl.duration && resumeSeconds < videoEl.duration - RESUME_END_MARGIN_SECONDS) {
                    videoEl.currentTime = resumeSeconds;
                }
                videoEl.removeEventListener('loadedmetadata', seekToResume);
            };
            videoEl.addEventListener('loadedmetadata', seekToResume);
        }

        videoEl.play().catch(() => {});
        // Scroll the docked player into view - it's the first thing in the results section,
        // but the user may already be scrolled down browsing episodes when they tap one.
        playerDock.scrollIntoView({ behavior: 'smooth', block: 'start' });

        clearInterval(positionSaveInterval);
        positionSaveInterval = setInterval(savePlaybackPosition, 5000);

        loadSubtitleTracks(file.libraryId);
        loadAudioTracks(file.libraryId);
    }

    // ---------- Subtitles ----------
    async function loadSubtitleTracks(libraryId) {
        // Clear any <track> elements from the previous file before (maybe) adding new ones.
        videoEl.querySelectorAll('track').forEach((t) => t.remove());
        subtitleMenuWrap.classList.add('hidden');
        subtitleMenu.innerHTML = '';

        let tracks = [];
        try {
            tracks = await API.subtitleTracks(libraryId);
        } catch (e) {
            return; // not fatal - subtitles just won't be offered for this file
        }
        if (!tracks.length) return;

        tracks.forEach((track, i) => {
            const el = document.createElement('track');
            el.kind = 'subtitles';
            el.label = subtitleTrackLabel(track, i);
            el.srclang = track.language || 'und';
            el.src = API.subtitleTrackUrl(libraryId, track.ffmpegStreamIndex);
            videoEl.appendChild(el);
        });

        // Off by default - browsers otherwise sometimes auto-enable the first track, which can
        // surprise a user who didn't ask for subtitles.
        Array.from(videoEl.textTracks).forEach((t) => { t.mode = 'disabled'; });

        buildSubtitleMenu(tracks);
        subtitleMenuWrap.classList.remove('hidden');
    }

    function subtitleTrackLabel(track, index) {
        const langNames = { eng: 'English', hin: 'Hindi', spa: 'Spanish', fre: 'French', fra: 'French', ger: 'German', deu: 'German' };
        if (track.language && langNames[track.language.toLowerCase()]) return langNames[track.language.toLowerCase()];
        if (track.title) return track.title;
        return `Track ${index + 1}`;
    }

    function buildSubtitleMenu(tracks) {
        subtitleMenu.innerHTML = '';

        const offBtn = document.createElement('button');
        offBtn.className = 'dropdown-item';
        offBtn.textContent = 'Off';
        offBtn.addEventListener('click', () => selectSubtitleTrack(-1));
        subtitleMenu.appendChild(offBtn);

        tracks.forEach((track, i) => {
            const btn = document.createElement('button');
            btn.className = 'dropdown-item';
            btn.textContent = subtitleTrackLabel(track, i);
            btn.addEventListener('click', () => selectSubtitleTrack(i));
            subtitleMenu.appendChild(btn);
        });
    }

    function selectSubtitleTrack(selectedIndex) {
        Array.from(videoEl.textTracks).forEach((t, i) => {
            t.mode = i === selectedIndex ? 'showing' : 'disabled';
        });
        subtitleMenuWrap.classList.toggle('active', selectedIndex >= 0);
        subtitleMenu.classList.add('hidden');
    }

    subtitleBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        document.querySelectorAll('.dropdown-menu').forEach((m) => {
            if (m !== subtitleMenu) m.classList.add('hidden');
        });
        subtitleMenu.classList.toggle('hidden');
    });
    document.addEventListener('click', () => subtitleMenu.classList.add('hidden'));

    // ---------- Audio tracks ----------
    // Browsers can't switch between an MKV's embedded audio tracks during playback, so picking
    // a language re-points the player at a server-side remuxed variant (video + just that audio
    // track) and reloads - this loses buffered data and needs a re-seek back to where playback
    // was, unlike subtitles which can just be toggled live via the browser's own track API.
    async function loadAudioTracks(libraryId) {
        audioMenuWrap.classList.add('hidden');
        audioMenu.innerHTML = '';

        let tracks = [];
        try {
            tracks = await API.audioTracks(libraryId);
        } catch (e) {
            return; // not fatal - the track switcher just won't be offered for this file
        }
        if (!tracks.length) return;

        buildAudioMenu(libraryId, tracks);
        audioMenuWrap.classList.remove('hidden');
    }

    function audioTrackLabel(track, index) {
        const langNames = { eng: 'English', hin: 'Hindi', spa: 'Spanish', fre: 'French', fra: 'French', ger: 'German', deu: 'German', tam: 'Tamil', tel: 'Telugu' };
        if (track.language && langNames[track.language.toLowerCase()]) return langNames[track.language.toLowerCase()];
        if (track.title) return track.title;
        return `Track ${index + 1}`;
    }

    function buildAudioMenu(libraryId, tracks) {
        audioMenu.innerHTML = '';
        tracks.forEach((track, i) => {
            const btn = document.createElement('button');
            btn.className = 'dropdown-item';
            btn.textContent = audioTrackLabel(track, i);
            btn.addEventListener('click', () => switchAudioTrack(libraryId, track.ffmpegStreamIndex));
            audioMenu.appendChild(btn);
        });
    }

    async function switchAudioTrack(libraryId, trackIndex) {
        audioMenu.classList.add('hidden');
        const resumeAt = videoEl.currentTime;
        const wasPlaying = !videoEl.paused;

        showLoading('Switching audio...');
        try {
            await API.selectAudioTrack(libraryId, trackIndex);
            reloadVideoAt(resumeAt, wasPlaying);
        } catch (e) {
            showToast(e.message, true);
        } finally {
            hideLoading();
        }
    }

    function reloadVideoAt(resumeAt, wasPlaying) {
        const speedBeforeReload = videoEl.playbackRate;
        const seekOnReady = () => {
            videoEl.currentTime = resumeAt;
            videoEl.playbackRate = speedBeforeReload; // load() resets this to 1x otherwise
            if (wasPlaying) videoEl.play().catch(() => {});
            videoEl.removeEventListener('loadedmetadata', seekOnReady);
        };
        videoEl.addEventListener('loadedmetadata', seekOnReady);
        // Cache-bust so the browser doesn't reuse a previously cached response for this same
        // URL from before the audio track switch - the bytes at each range have changed.
        videoEl.src = API.streamUrl(currentPlayingLibraryId) + '?v=' + Date.now();
        videoEl.load();
    }

    audioBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        document.querySelectorAll('.dropdown-menu').forEach((m) => {
            if (m !== audioMenu) m.classList.add('hidden');
        });
        audioMenu.classList.toggle('hidden');
    });
    document.addEventListener('click', () => audioMenu.classList.add('hidden'));

    // ---------- Playback speed ----------
    const PLAYBACK_SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];

    function buildSpeedMenu() {
        speedMenu.innerHTML = '';
        PLAYBACK_SPEEDS.forEach((speed) => {
            const btn = document.createElement('button');
            btn.className = 'dropdown-item';
            btn.textContent = speed + 'x';
            btn.dataset.speed = speed;
            btn.addEventListener('click', () => {
                setPlaybackSpeed(speed);
                speedMenu.classList.add('hidden');
            });
            speedMenu.appendChild(btn);
        });
    }
    buildSpeedMenu();

    function setPlaybackSpeed(speed) {
        videoEl.playbackRate = speed;
        speedBtn.textContent = speed + 'x';
        speedMenu.querySelectorAll('.dropdown-item').forEach((btn) => {
            btn.classList.toggle('active', Number(btn.dataset.speed) === speed);
        });
    }

    speedBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        document.querySelectorAll('.dropdown-menu').forEach((m) => {
            if (m !== speedMenu) m.classList.add('hidden');
        });
        speedMenu.classList.toggle('hidden');
    });
    document.addEventListener('click', () => speedMenu.classList.add('hidden'));

    function savePlaybackPosition() {
        if (currentPlayingLibraryId == null || !videoEl.duration || videoEl.seeking) return;
        API.savePosition(currentPlayingLibraryId, videoEl.currentTime).catch(() => {});
    }

    function closePlayer() {
        savePlaybackPosition();
        clearInterval(positionSaveInterval);
        currentPlayingLibraryId = null;
        videoEl.pause();
        videoEl.removeAttribute('src');
        videoEl.load();
        playerDock.classList.add('hidden');
        isPlayerMinimized = false;
        playerDock.classList.remove('minimized');
        setLocked(false);
        if (document.fullscreenElement) {
            document.exitFullscreen?.();
        }
    }

    function minimizePlayer() {
        if (playerDock.classList.contains('hidden') || isPlayerMinimized) return;
        isPlayerMinimized = true;
        playerDock.classList.add('minimized');
        setLocked(false); // lock doesn't make sense on the tiny mini view
    }

    function restorePlayer() {
        isPlayerMinimized = false;
        playerDock.classList.remove('minimized');
        playerDock.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    closePlayerBtn.addEventListener('click', closePlayer);
    minimizePlayerBtn.addEventListener('click', minimizePlayer);
    minimizedCloseBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        closePlayer();
    });
    minimizedOverlay.addEventListener('click', restorePlayer);

    videoEl.addEventListener('waiting', () => bufferingSpinner.classList.remove('hidden'));
    videoEl.addEventListener('playing', () => bufferingSpinner.classList.add('hidden'));
    videoEl.addEventListener('canplay', () => bufferingSpinner.classList.add('hidden'));

    videoEl.addEventListener('play', () => {
        centerPlayIcon.classList.add('hidden');
        centerPauseIcon.classList.remove('hidden');
    });
    videoEl.addEventListener('pause', () => {
        centerPlayIcon.classList.remove('hidden');
        centerPauseIcon.classList.add('hidden');
        savePlaybackPosition();
    });

    // Best-effort save when the tab is being closed/backgrounded - the periodic interval alone
    // could miss up to 5 seconds of progress right before that happens.
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') savePlaybackPosition();
    });

    centerPlayPauseBtn.addEventListener('click', togglePlayPause);

    videoEl.addEventListener('timeupdate', () => {
        if (!isSeeking && videoEl.duration) {
            seekBar.value = (videoEl.currentTime / videoEl.duration) * 100;
        }
        timeCurrent.textContent = formatTime(videoEl.currentTime);
    });
    videoEl.addEventListener('loadedmetadata', () => {
        timeDuration.textContent = formatTime(videoEl.duration);
    });

    let isSeeking = false;
    seekBar.addEventListener('input', () => { isSeeking = true; });
    seekBar.addEventListener('change', () => {
        if (videoEl.duration) {
            videoEl.currentTime = (seekBar.value / 100) * videoEl.duration;
        }
        isSeeking = false;
    });

    function formatTime(sec) {
        if (!isFinite(sec) || sec < 0) return '0:00';
        const m = Math.floor(sec / 60);
        const s = Math.floor(sec % 60);
        return `${m}:${String(s).padStart(2, '0')}`;
    }

    fullscreenBtn.addEventListener('click', () => {
        const wrap = document.querySelector('.player-video-wrap');
        if (!document.fullscreenElement) {
            wrap.requestFullscreen?.().catch(() => {});
        } else {
            document.exitFullscreen?.();
        }
    });

    // Auto-hide controls
    let hideControlsTimer = null;
    function scheduleHideControls() {
        clearTimeout(hideControlsTimer);
        if (videoEl.paused || isLocked) return; // stay visible while paused - nothing to get out of the way of
        hideControlsTimer = setTimeout(() => {
            if (!videoEl.paused && !isLocked) playerControls.classList.add('faded');
        }, 3000);
    }
    function showControls() {
        playerControls.classList.remove('faded');
        scheduleHideControls();
    }
    playerDock.addEventListener('pointermove', () => {
        if (!isGestureDragging) showControls();
    });
    videoEl.addEventListener('play', scheduleHideControls);
    videoEl.addEventListener('pause', () => {
        clearTimeout(hideControlsTimer);
        playerControls.classList.remove('faded');
    });

    // Lock
    let isLocked = false;
    function setLocked(locked) {
        isLocked = locked;
        lockOverlay.classList.toggle('hidden', !locked);
        playerControls.classList.toggle('hidden', locked);
        if (!locked) showControls();
    }
    lockBtn.addEventListener('click', () => setLocked(true));
    unlockBtn.addEventListener('click', () => setLocked(false));

    // The gesture zones (left half / right half of the video) sit on top of videoEl and
    // combine four things: single tap toggles controls, double-tap seeks +-10s, and a vertical
    // drag adjusts brightness (left) or volume (right). All need their own touch handling since
    // a plain click listener on videoEl itself is covered by these zones anyway.
    const TAP_MAX_DURATION_MS = 250;
    const TAP_MAX_MOVEMENT_PX = 10;
    const DOUBLE_TAP_WINDOW_MS = 220;
    const DOUBLE_TAP_MAX_DISTANCE_PX = 100;
    const DRAG_ENGAGE_THRESHOLD_PX = 6; // vertical movement before a touch commits to being a drag, not a tap
    const SEEK_STEP_SECONDS = 10;
    const BRIGHTNESS_SENSITIVITY = 1.8; // higher = less finger travel needed for full range
    const VOLUME_SENSITIVITY = 1.8;

    let currentBrightness = 1; // 0.3-1.5, applied via the #videoEl CSS filter custom property
    let lastTapTime = 0;
    let lastTapX = 0;
    let lastTapY = 0;
    // True while a brightness/volume drag is in progress. touchmove also fires a synthetic
    // pointermove, which would otherwise re-trigger showControls() (see playerDock's
    // pointermove listener below) and un-fade .player-controls - a full-cover, higher-z-index
    // element - right on top of the gesture zones mid-drag, silently swallowing touches for the
    // next few seconds until it auto-hides again. Gestures already manage control visibility
    // themselves via toggleControls(), so this listener should stay out of the way while one is active.
    let isGestureDragging = false;

    function setupGestureZone(zone, side) {
        let touchStartTime = 0;
        let startX = 0;
        let startY = 0;
        let lastY = 0;
        let maxMovement = 0;
        let dragMode = null; // null | 'brightness' | 'volume'

        zone.addEventListener('touchstart', (e) => {
            if (isLocked) return;
            const touch = e.changedTouches[0];
            startX = touch.clientX;
            startY = touch.clientY;
            lastY = touch.clientY;
            touchStartTime = Date.now();
            maxMovement = 0;
            dragMode = null;
        }, { passive: true });

        zone.addEventListener('touchmove', (e) => {
            if (isLocked) return;
            const touch = e.changedTouches[0];
            const dx = touch.clientX - startX;
            const dyFromStart = touch.clientY - startY;
            maxMovement = Math.max(maxMovement, Math.hypot(dx, dyFromStart));

            if (!dragMode && Math.abs(dyFromStart) > DRAG_ENGAGE_THRESHOLD_PX && Math.abs(dyFromStart) > Math.abs(dx)) {
                dragMode = side === 'left' ? 'brightness' : 'volume';
                isGestureDragging = true;
            }
            if (dragMode) {
                e.preventDefault();
                // Incremental delta since the LAST move event (not the drag's start point) -
                // tracks the finger 1:1 regardless of how far the drag has already traveled,
                // so the value follows the finger precisely instead of lagging/overshooting.
                const zoneHeight = zone.getBoundingClientRect().height || 1;
                const stepDy = touch.clientY - lastY;
                const stepDelta = (-stepDy / zoneHeight) * (dragMode === 'brightness' ? BRIGHTNESS_SENSITIVITY : VOLUME_SENSITIVITY);
                lastY = touch.clientY;

                if (dragMode === 'brightness') {
                    currentBrightness = clamp(currentBrightness + stepDelta * 1.2, 0.3, 1.5);
                    applyBrightness();
                    showGestureIndicator('brightness', currentBrightness / 1.5);
                } else {
                    videoEl.volume = clamp(videoEl.volume + stepDelta, 0, 1);
                    showGestureIndicator('volume', videoEl.volume);
                }
            }
        }, { passive: false });

        zone.addEventListener('touchend', (e) => {
            if (dragMode) {
                dragMode = null;
                isGestureDragging = false;
                hideGestureIndicatorSoon();
                return;
            }

            const wasTap = !isLocked
                && Date.now() - touchStartTime < TAP_MAX_DURATION_MS
                && maxMovement < TAP_MAX_MOVEMENT_PX;
            if (!wasTap) return;

            e.preventDefault(); // suppress the synthetic click that would otherwise follow
            const touch = e.changedTouches[0];
            const now = Date.now();
            const isDoubleTap = now - lastTapTime < DOUBLE_TAP_WINDOW_MS
                && Math.hypot(touch.clientX - lastTapX, touch.clientY - lastTapY) < DOUBLE_TAP_MAX_DISTANCE_PX;

            if (isDoubleTap) {
                // The first tap already toggled controls (see below) - undo that so a
                // double-tap seek doesn't also leave the controls flickered.
                toggleControls();
                lastTapTime = 0;
                seekBy(side === 'left' ? -SEEK_STEP_SECONDS : SEEK_STEP_SECONDS, side);
            } else {
                lastTapTime = now;
                lastTapX = touch.clientX;
                lastTapY = touch.clientY;
                // Toggle immediately - no perceptible delay for the common single-tap case.
                // If a second tap follows within the window, the branch above undoes this.
                toggleControls();
            }
        });

        zone.addEventListener('touchcancel', () => {
            // The OS can interrupt a touch mid-drag (e.g. a notification pull-down) - without
            // this, dragMode/isGestureDragging would stay stuck true with no touchend to clear
            // them, permanently blocking the pointermove-based controls listener.
            dragMode = null;
            isGestureDragging = false;
            hideGestureIndicatorSoon();
        });

        zone.addEventListener('click', () => {
            if (!isLocked && maxMovement < TAP_MAX_MOVEMENT_PX && !('ontouchstart' in window)) {
                toggleControls(); // non-touch (mouse) fallback - touch devices are handled above
            }
        });
    }
    setupGestureZone(gestureLeft, 'left');
    setupGestureZone(gestureRight, 'right');
    setupCenterTapZone(gestureCenter);

    // The center zone is simpler than left/right - just a tap to play/pause (no double-tap
    // seek, no vertical drag), so it doesn't need the full setupGestureZone machinery.
    function setupCenterTapZone(zone) {
        let touchStartTime = 0;
        let startX = 0;
        let startY = 0;
        let maxMovement = 0;

        zone.addEventListener('touchstart', (e) => {
            if (isLocked) return;
            const touch = e.changedTouches[0];
            startX = touch.clientX;
            startY = touch.clientY;
            touchStartTime = Date.now();
            maxMovement = 0;
        }, { passive: true });

        zone.addEventListener('touchmove', (e) => {
            if (isLocked) return;
            const touch = e.changedTouches[0];
            maxMovement = Math.max(maxMovement, Math.hypot(touch.clientX - startX, touch.clientY - startY));
        }, { passive: true });

        zone.addEventListener('touchend', (e) => {
            const wasTap = !isLocked
                && Date.now() - touchStartTime < TAP_MAX_DURATION_MS
                && maxMovement < TAP_MAX_MOVEMENT_PX;
            if (!wasTap) return;
            e.preventDefault();
            togglePlayPause();
        });

        zone.addEventListener('click', () => {
            if (!isLocked && !('ontouchstart' in window)) togglePlayPause();
        });
    }

    function togglePlayPause() {
        if (videoEl.paused) videoEl.play(); else videoEl.pause();
        showControls();
    }

    function clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    function applyBrightness() {
        videoEl.style.setProperty('--player-brightness', currentBrightness.toFixed(2));
    }

    function seekBy(deltaSeconds, side) {
        if (!videoEl.duration) return;
        videoEl.currentTime = clamp(videoEl.currentTime + deltaSeconds, 0, videoEl.duration);
        const indicator = side === 'left' ? seekIndicatorLeft : seekIndicatorRight;
        indicator.classList.remove('hidden');
        clearTimeout(indicator._hideTimer);
        indicator._hideTimer = setTimeout(() => indicator.classList.add('hidden'), 600);
    }

    let gestureIndicatorHideTimer = null;
    function showGestureIndicator(kind, fraction) {
        clearTimeout(gestureIndicatorHideTimer);
        gestureIndicator.classList.remove('hidden');
        gestureBarFill.style.height = Math.round(clamp(fraction, 0, 1) * 100) + '%';
        gestureValue.textContent = Math.round(clamp(fraction, 0, 1) * 100) + '%';
        gestureIcon.innerHTML = kind === 'brightness'
            ? '<circle cx="12" cy="12" r="4" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 2v2M12 20v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M2 12h2M20 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>'
            : '<path d="M4 9v6h4l5 5V4L8 9H4z" fill="currentColor"/>' + (fraction > 0.02 ? '<path d="M16 9a4 4 0 010 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>' : '');
    }
    function hideGestureIndicatorSoon() {
        clearTimeout(gestureIndicatorHideTimer);
        gestureIndicatorHideTimer = setTimeout(() => gestureIndicator.classList.add('hidden'), 500);
    }

    function toggleControls() {
        if (playerControls.classList.contains('faded')) {
            showControls();
        } else {
            clearTimeout(hideControlsTimer);
            playerControls.classList.add('faded');
        }
    }

    // ---------- Wiring ----------
    searchForm.addEventListener('submit', (e) => {
        e.preventDefault();
        runSearch(searchInput.value);
    });

    // Tapping into the search box switches to the "Search" tab (Recent searches only). Only
    // acts when currently on the home screen - once results/library are showing, the back
    // button already handles returning to a prior view, and we don't want focusing the box
    // while browsing results to yank the user back to Recent.
    // Tapping into the search box always switches to the Search tab (Recent searches only),
    // no matter which view is currently showing (home, library, results, ...).
    searchInput.addEventListener('focus', () => {
        if (!playerDock.classList.contains('hidden') && !isPlayerMinimized) {
            minimizePlayer();
        }
        if (recentSection.classList.contains('hidden')) {
            showView('recent');
        }
    });

    // ---------- Search suggestions ----------
    // Matches what's typed against titles you've already searched or downloaded - e.g. typing
    // "peak" suggests "Peaky Blinders" if it's in your recent searches or library. This is
    // local matching only (no bot query per keystroke, which would hammer it unnecessarily) -
    // picking a suggestion just fills the box and runs a normal search.
    let librarySuggestionTitles = [];
    let librarySuggestionsLoaded = false;

    async function ensureLibrarySuggestionTitles() {
        if (librarySuggestionsLoaded) return;
        librarySuggestionsLoaded = true; // only try once per page load - a failure isn't worth retrying on every keystroke
        try {
            const items = await API.library();
            librarySuggestionTitles = items.map((item) => item.cleanTitle || item.displayName);
        } catch (e) {
            // Non-critical - suggestions just fall back to recent searches alone.
        }
    }

    function collectSuggestions(query) {
        const needle = query.trim().toLowerCase();
        if (!needle) return [];

        const seen = new Set();
        const results = [];

        const addCandidate = (title, source) => {
            const key = title.toLowerCase();
            if (!key.includes(needle) || seen.has(key)) return;
            seen.add(key);
            results.push({ title, source });
        };

        librarySuggestionTitles.forEach((title) => addCandidate(title, 'library'));
        getRecent().forEach((title) => addCandidate(title, 'recent'));

        return results.slice(0, 6);
    }

    function highlightMatch(title, query) {
        const idx = title.toLowerCase().indexOf(query.trim().toLowerCase());
        if (idx === -1) return title;
        const before = title.slice(0, idx);
        const match = title.slice(idx, idx + query.trim().length);
        const after = title.slice(idx + query.trim().length);
        const span = document.createElement('span');
        span.appendChild(document.createTextNode(before));
        const strong = document.createElement('strong');
        strong.textContent = match;
        span.appendChild(strong);
        span.appendChild(document.createTextNode(after));
        return span;
    }

    function renderSearchSuggestions(query) {
        const suggestions = collectSuggestions(query);
        searchSuggestions.innerHTML = '';

        if (!suggestions.length) {
            searchSuggestions.classList.add('hidden');
            return;
        }

        suggestions.forEach(({ title, source }) => {
            const btn = document.createElement('button');
            btn.className = 'search-suggestion-item';
            btn.innerHTML = source === 'library'
                ? '<svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="14" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M8 20h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>'
                : '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 7v5l3 3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
            btn.appendChild(highlightMatch(title, query));
            btn.addEventListener('click', () => {
                searchSuggestions.classList.add('hidden');
                runSearch(title);
            });
            searchSuggestions.appendChild(btn);
        });

        searchSuggestions.classList.remove('hidden');
    }

    searchInput.addEventListener('input', () => {
        ensureLibrarySuggestionTitles();
        renderSearchSuggestions(searchInput.value);
    });
    document.addEventListener('click', (e) => {
        if (!searchSuggestions.contains(e.target) && e.target !== searchInput) {
            searchSuggestions.classList.add('hidden');
        }
    });

    backBtn.addEventListener('click', () => {
        if (!playerDock.classList.contains('hidden') && !isPlayerMinimized) {
            minimizePlayer();
        }
        searchInput.blur();
        searchInput.value = '';
        showView('home');
        loadContinueWatching();
    });

    libraryBtn.addEventListener('click', () => loadLibrary(true));
    clearRecentBtn.addEventListener('click', clearRecent);

    // ---------- Connection status ----------
    // Polls independently of any user action, so a dropped connection (laptop slept, network
    // blip, Telegram down) shows up as a clear persistent banner rather than only surfacing
    // later as a confusing "bot did not respond" error on whatever the user happens to tap next.
    const CONNECTION_STATE_LABELS = {
        waiting_for_network: 'Waiting for network...',
        connecting_to_proxy: 'Connecting to proxy...',
        connecting: 'Connecting to Telegram...',
        updating: 'Syncing with Telegram...',
        unknown: 'Reconnecting to Telegram...',
    };

    async function pollConnectionStatus() {
        try {
            const status = await API.status();
            connectionBanner.classList.toggle('hidden', status.connected);
            if (!status.connected) {
                connectionBannerText.textContent = CONNECTION_STATE_LABELS[status.state] || CONNECTION_STATE_LABELS.unknown;
            }
        } catch (e) {
            // The backend itself is unreachable (not just Telegram) - same banner, most honest
            // generic message available.
            connectionBanner.classList.remove('hidden');
            connectionBannerText.textContent = 'Reconnecting to the server...';
        }
    }
    pollConnectionStatus();
    setInterval(pollConnectionStatus, 10000);

    // ---------- Init ----------
    renderRecent();
    showView('home');
    loadContinueWatching();
})();
