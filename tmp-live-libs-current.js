var swiper3 = new Swiper(".dar-home", {
    modules: [EffectPanorama],
    effect: 'panorama',
    navigation: {
        nextEl: ".dar-home .fa-chevron-right",
        prevEl: ".dar-home .fa-chevron-left",
    },
    grabCursor: true,
    pagination: {
        el: '.swiper-pagination',
        dynamicBullets: true,
        dynamicMainBullets: 3,
    },
    panoramaEffect: {
    	depth: 200,
    	rotate: 50,
  	},
    loop: true,
    centeredSlides: true,
    spaceBetween: 10,
    slidesPerView: 1,
    breakpoints: {
        /*2000: {
            slidesPerView: 1,
            centeredSlides: false,
            effect: 'slide',
            spaceBetween: 20,
        },*/
        860: {
            slidesPerView: 1,
        },
    },
    autoplay: {
        delay: 1000000000,
        disableOnInteraction: false,
    },
});

var swiper = new Swiper(".dar-short", {
    navigation: {
        nextEl: ".dar-short .fa-chevron-right",
        prevEl: ".dar-short .fa-chevron-left",
    },
    spaceBetween: 20,
    slidesPerView: 1.5,
    breakpoints: {
        860: {
            slidesPerView: 5,
        },
    },
});

var swiper = new Swiper(".dar-short1", {
    navigation: {
        nextEl: ".dar-short1 .fa-chevron-right",
        prevEl: ".dar-short1 .fa-chevron-left",
    },
    spaceBetween: 20,
    slidesPerView: 1.2,
    breakpoints: {
        860: {
            slidesPerView: 4,
        },
    },
});

// MODAL 17.08.24
$(document).ready(function() {
    $(document).on('click', 'a[name="modal"]', function(event) {
        event.preventDefault();
        var modalId = $(this).attr('href');
        $('.modal').hide();
        $(modalId).show();
        $('body').css('overflow', 'hidden');
        $('.e-overlay').remove();
        $(modalId).after('<div class="e-overlay active"></div>');
    }).on('click', '.modal .fa-xmark, .modal .fa-times, .e-overlay', function() {
        $('.modal').hide();
        $('body').css('overflow', 'auto');
        $('.header-ref, .smart-modal, .mobmenu, .mobmenu_btn').removeClass("active");
        $('.e-overlay').remove();
    });
});

// HEADER SEARCH
$(".header-search_btn, .header-search .fa-xmark, .header-search_top").click(function () {
    $('.header-search, .header-search_top').toggleClass('active');
    $('.header-menu').toggleClass('none');
});
$(document).on("click", function (e) {
    if (!$(e.target).closest(".header-search_btn, .header-search").length) {
        $(".header-search, .header-search_top").removeClass("active");
        $('.header-menu').removeClass('none');
    }
    e.stopPropagation();
});
$(".header-search input").on("input click", function() {
    if ($(this).val()) {
        $('.header-search_top').removeClass("active");
    } else {
        $('.header-search_top').addClass("active");
    }
});

// HEADER LANG
$(document).on('click', function(e) {
    if ($(e.target).closest('.header-lang').length) {
        $('.header-lang').toggleClass('active');
    } else if (!$(e.target).closest('.header-user').length) {
        $('.header-lang').removeClass('active');
    }
});

// HEADER MENU
$('.header-menu_btn').click(function () {
    $(this).toggleClass('active');
    $('.header-menu').toggleClass('active');
    $('.e-overlay').length ? $('.e-overlay').remove() : $('.header-menu').after('<div class="e-overlay active"></div>');
});
$('.header-menu > div > span').click(function () {
    $(this).parent().toggleClass('active');
});
$(document).on('click', '.e-overlay', function () {
    $('.e-overlay').remove();
    $('.header-menu, .header-menu_btn').removeClass('active');
});

// HEADER USER
$(document).on('click', function(e) {
    if ($(e.target).closest('.header-user').length) {
        $('.header-user').toggleClass('active');
    } else if (!$(e.target).closest('.header-user').length) {
        $('.header-user').removeClass('active');
    }
});

// TABS
$('[data-tab]').on('click', function() {
	var tab = $(this).data('tab');
	$('[data-tab]').removeClass('active');
	$(this).addClass('active');
	$('.tab-content').removeClass('active');
	$('#' + tab).addClass('active');
});

// TABS 2
$('.dar-tr_item').each(function() {
    const $container = $(this);
    $container.find('.dar-tr_tabs span').on('click', function() {
        const tab = $(this).data('tab1');
        $container.find('.dar-tr_tabs span').removeClass('active');
        $container.find('.dar-tr_content').removeClass('active');
        $(this).addClass('active');
        $container.find('#' + tab).addClass('active');
    });
});

// COMMENT DET 0.1
$('.dle-comm_det').on('click', function(){
	$(this).toggleClass('active');
});

$(document).on("click", function (e) {
    if (!$(e.target).closest(".dle-comm_det").length) {
        $(".dle-comm_det").removeClass("active");
    }
    e.stopPropagation();
});

// RATINGS */
$(".rating-bar").each(function () {
    var $this = $(this),$count = $this.find(".rating-bar_count"),countValue = parseFloat($count.text(), 10),percentage = (countValue * 10).toFixed(1);
    $this.append('<div class="rating-bar_fill"><span style="width: ' + percentage + '%"></span></div>');
});
$(".rating-color, .rating-color1").each(function () {
    var $this = $(this),rating = parseInt($this.text(), 10);
    $this.addClass(rating < 4 ? "low" : rating < 7 ? "middle" : "");
});

// FULL TEXT
$('[data-rows]').each(function() {
    var a = $(this), b = parseFloat(a.css('line-height')), c = a.data('rows'), d = a.outerHeight(), h = b*c;
    if ( d > h ) {
        a.attr('style', '-webkit-line-clamp: '+c+'').after('<a class="expand-btn">Expand text</a>');
    };
});
$(document).on('click', '.expand-btn', function() {
    $(this).prev().removeClass('line-clamp').removeAttr('style'); 
    $(this).remove();
});

//SHARE
document.querySelectorAll('.ta-short_share').forEach(button => {
    button.addEventListener('click', function (e) {
        e.preventDefault();
        const title = this.dataset.title || document.title;
        const url = this.dataset.url || window.location.href;
        if (navigator.share) {
            navigator.share({
                title: title,
                url: url
            }).catch(console.error);
        } else {
            alert("Your browser does not support the standard share window.");
        }
    });
});

// ACCORDION
jQuery(function ($) {
  $(".accord-text").css("display", "none");
  $(".accord").click(function () {
      $(".accord").not(this).removeClass("active");
      $(".accord").not(this).next().slideUp(300);
      $(this).toggleClass("active");
      $(this).next().slideToggle(300);
  });
});

// TORRENT FILTER BY LANGUAGE
(function () {
    const root = document.querySelector('#torrents');
    if (!root) return;

    const tabsBar = root.querySelector('.ta-full_tabs');
    const itemsWrap = root.querySelector('.dar-tr_items');
    if (!tabsBar || !itemsWrap) return;

    const items = Array.from(itemsWrap.querySelectorAll('.dar-tr_item.section'));

    const langSet = new Set();
    items.forEach(item => {
        const aboutBlocks = item.querySelectorAll('.dar-tr_content');
        let langs = [];

        for (const block of aboutBlocks) {
            const liList = block.querySelectorAll('li');
            for (const li of liList) {
                const spans = li.querySelectorAll('span');
                if (spans.length >= 2 && spans[0].textContent.trim().toLowerCase() === 'language:') {
                    const val = spans[1].textContent || '';
                    langs = val.split(',').map(s => s.trim()).filter(Boolean);
                    break;
                }
            }
            if (langs.length) break;
        }

        item.dataset.languages = JSON.stringify(langs.map(l => l.toLowerCase()));
        langs.forEach(l => langSet.add(l));
    });

    if (langSet.size === 0) return;

    const existingActive = tabsBar.querySelector('.active')?.textContent?.trim() || null;
    tabsBar.innerHTML = '';

    const makeTab = (label) => {
        const span = document.createElement('span');
        span.textContent = label;
        span.setAttribute('role', 'button');
        span.tabIndex = 0;
        return span;
    };

    const tabs = [];
    const allTab = makeTab('All');
    tabs.push(allTab);
    [...langSet].sort((a, b) => a.localeCompare(b)).forEach(l => {
        tabs.push(makeTab(l));
    });

    tabs.forEach(t => tabsBar.appendChild(t));

    const initial = tabs.find(t => t.textContent.trim().toLowerCase() === (existingActive || '').toLowerCase()) || allTab;
    setActive(initial);

    tabsBar.addEventListener('click', (e) => {
        const el = e.target.closest('span');
        if (!el || !tabsBar.contains(el)) return;
        setActive(el);
    });

    tabsBar.addEventListener('keydown', (e) => {
        if ((e.key === 'Enter' || e.key === ' ') && e.target instanceof HTMLElement && e.target.closest('.ta-full_tabs')) {
            e.preventDefault();
            setActive(e.target);
        }
    });

    function setActive(tabEl) {
        tabs.forEach(t => t.classList.remove('active'));
        tabEl.classList.add('active');

        const chosen = tabEl.textContent.trim().toLowerCase();
        filterItems(chosen);
    }

    function filterItems(chosen) {
        const showAll = (chosen === 'all');
        items.forEach(item => {
            const langs = JSON.parse(item.dataset.languages || '[]');
            const fit = showAll || langs.includes(chosen);
            item.style.display = fit ? '' : 'none';
        });
    }
})();

// DOWNLOAD VIDEO
(function () {
    const root = document.getElementById('download');
    if (!root) return;

    let playlist = [];
    let sizeMap = {};

    (function extractPlaylist() {
        const script = document.querySelector('div[id^=player] + script');
        if (!script) return;

        const m = script.textContent.match(/atob\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)/);
        if (!m) return;
        const b64 = m[1];

        let decodedJs = '';
        try {
            decodedJs = atob(b64);
        } catch (e) { return; }

        const fileMatch = decodedJs.match(/file:'(.*)', poster/);
        if (!fileMatch) return;

        const fileString = fileMatch[1];
        try {
            playlist = JSON.parse(fileString);
        } catch (e) { return; }
    })();

    if (!playlist) return;

    (function dlVideoInit() {
        const contentTitle = String(document.querySelector('h1').innerText ?? '')
            .replace('–', '-')
            .normalize('NFKD')
            .replace(/['"`]/g, '')
            .replace(/[()[\]{}<>]/g, '')
            .replace(/[^0-9A-Za-zА-Яа-яЁё\s._-]+/g, '')
            .replace(/[\s_]+/g, '.')
            .replace(/\.+/g, '.')
            .replace(/^\.|\.$/g, '')
            .trim();

        if (playlist && Array.isArray(playlist) && playlist.length) {
            const selSeason = root.querySelector('select[name="dar-dl_season"]');
            const selEpisode = root.querySelector('select[name="dar-dl_episode"]');
            const selQuality = root.querySelector('select[name="dar-dl_quality"]');
            const selLang = root.querySelector('select[name="dar-dl_language"]');

            const itemsWrap = root.querySelector('.dar-tr_items');

            const isSeries = !!playlist[0]?.folder && Array.isArray(playlist[0].folder);

            // ---------- helpers ----------                    
            function clearSelect(sel, placeholder = '•••') {
                sel.innerHTML = '';
                const opt = document.createElement('option');
                opt.value = '';
                opt.textContent = placeholder;
                sel.appendChild(opt);
            }

            function addOption(sel, value, text) {
                const first = sel.options[0];
                if (first && first.value === '') {
                    sel.remove(0);
                }
                const opt = document.createElement('option');
                opt.value = value;
                opt.textContent = text;
                sel.appendChild(opt);
            }

            function show(el, yes) {
                el.style.display = yes ? '' : 'none';
            }

            function titleCase(s) {
                return String(s || '')
                    .replace(/[-_]+/g, ' ')
                    .trim()
                    .split(/\s+/)
                    .map(w => w ? (w[0].toUpperCase() + w.slice(1)) : w)
                    .join(' ');
            }

            async function loadSizeMap() {
                const newsId = (window.location.pathname.match(/\/(\d+)-/) || [])[1];
                const userHash = window.dle_login_hash;

                if (!newsId || !userHash) {
                    sizeMap = {};
                    return;
                }

                const url =
                    `/engine/ajax/controller.php?mod=dh&action=sizes` +
                    `&news_id=${encodeURIComponent(newsId)}` +
                    `&user_hash=${encodeURIComponent(userHash)}`;

                try {
                    const response = await fetch(url, {
                        method: 'GET',
                        headers: { 'Accept': 'application/json' }
                    });

                    if (!response.ok) {
                        sizeMap = {};
                        return;
                    }

                    const data = await response.json();
                    sizeMap = data && typeof data === 'object' ? data : {};
                } catch (e) {
                    sizeMap = {};
                }
            }

            function formatBytes(bytes) {
                const n = Number(bytes);
                if (!Number.isFinite(n) || n <= 0) return '';

                const units = ['b', 'Kb', 'Mb', 'Gb', 'Tb'];
                let value = n;
                let unitIndex = 0;

                while (value >= 1024 && unitIndex < units.length - 1) {
                    value /= 1024;
                    unitIndex++;
                }

                if (value > 1000 && unitIndex < units.length - 1) {
                    value /= 1024;
                    unitIndex++;
                }

                const digits = value >= 100 || unitIndex === 0 ? 0 : value >= 10 ? 1 : 2;
                return `${value.toFixed(digits)} ${units[unitIndex]}`;
            }

            function getTotalSize(videoPath, audioPath) {
                const videoSize = Number(sizeMap?.[videoPath]);
                const audioSize = Number(sizeMap?.[audioPath]);

                if (!Number.isFinite(videoSize) || !Number.isFinite(audioSize)) {
                    return '';
                }

                return formatBytes(videoSize + audioSize);
            }

            function parseFileSet(fileStr, subtitleStr = '') {
                const parts = String(fileStr || '').split(',');
                const base = fileStr; // (parts.shift() || '').trim();
                const rels = parts.map(s => s.trim()).filter(Boolean);

                const audio = rels.filter(p => /\.m4a(\?|$)/i.test(p));
                const video = rels.filter(p => /\.mp4(\?|$)/i.test(p));

                const audioClean = audio.filter(p => !/\.urlset\/master\.m3u8$/i.test(p));
                const videoClean = video.filter(p => !/\.urlset\/master\.m3u8$/i.test(p));

                const languages = audioClean.map(p => {
                    const name = p.split('/').pop() || p;
                    const m = name.match(/_([^_]+)\.m4a$/i);
                    const key = m ? m[1] : name.replace(/\.m4a$/i, '');
                    return { key, label: titleCase(key), path: p };
                });

                const resolutions = videoClean.map(p => {
                    const name = p.split('/').pop() || p;
                    const m = name.match(/_(\d{3,4}p)\.mp4$/i);
                    const res = m ? m[1].toLowerCase() : 'mp4';
                    return { res, path: p };
                });

                const langMap = new Map();
                for (const l of languages) if (!langMap.has(l.key)) langMap.set(l.key, l);

                const resMap = new Map();
                for (const r of resolutions) if (!resMap.has(r.res)) resMap.set(r.res, r);

                const subtitles = String(subtitleStr || '')
                    .split(',')
                    .map(s => s.trim())
                    .filter(Boolean)
                    .map(s => {
                        const idx = s.indexOf(']');
                        const url = idx !== -1 ? s.slice(idx + 1).trim() : '';
                        const marker = '/public_files/';
                        const mi = url.indexOf(marker);
                        return mi !== -1 ? url.slice(mi + marker.length) : '';
                    })
                    .filter(Boolean);

                return {
                    base,
                    subtitles,
                    languages: Array.from(langMap.values()).sort((a, b) => {
                        return a.label.localeCompare(b.label);
                    }),
                    resolutions: Array.from(resMap.values()).sort((a, b) => {
                        const na = parseInt(a.res, 10) || 0;
                        const nb = parseInt(b.res, 10) || 0;
                        return nb - na;
                    })
                };
            }

            function buildName({ quality, res, langLabel, seasonIndex, episodeIndex }) {
                const q = String(quality || '').replace(/\s+/g, '');
                const r = String(res || '').toLowerCase();
                const l = String(langLabel || '').replace(/\s+/g, '.');
                if (isSeries) {
                    const s = (seasonIndex ?? 0) + 1;
                    const e = (episodeIndex ?? 0) + 1;
                    const ct = contentTitle.replace(/\.?\d{4}(?:-\d{4})?-?$/, '');
                    return `${ct}.S${s}E${e}.${r}.${l}`;
                }
                return `${contentTitle}.${q}.${r}.${l}`;
            }

            function makeDownloadHref(base, videoPath, audioPath, subtitlePaths, name) {
                const qs =
                    `?action=download` +
                    `&video=${encodeURIComponent(videoPath)}` +
                    `&audio=${encodeURIComponent(audioPath)}` +
                    (subtitlePaths ? `&subtitle=${encodeURIComponent(subtitlePaths)}` : '') +
                    `&name=${encodeURIComponent(name)}`;
                return base + qs;
            }

            function renderItems({ quality, base, resolutions, subtitles, audioSel, seasonIndex, episodeIndex }) {
                itemsWrap.innerHTML = '';

                if (!base || !resolutions.length || !audioSel?.path) {
                    return;
                }

                for (const r of resolutions) {
                    const name = buildName({
                        quality,
                        res: r.res,
                        langLabel: audioSel.label,
                        seasonIndex,
                        episodeIndex
                    });

                    const href = makeDownloadHref(
                        base,
                        r.path,
                        audioSel.path,
                        (subtitles || []).join(','),
                        name
                    );

                    const totalSize = getTotalSize(r.path, audioSel.path);

                    const hasSubtitles = Array.isArray(subtitles) && subtitles.length > 0;
                    const item = document.createElement('div');
                    item.className = 'dar-tr_item section';
                    item.innerHTML = `
                        <div class="dar-tr_title">
                            <div>${name}.mp4${totalSize ? ` <span>Size: ${totalSize}</span>` : ''}</div>
                            <a href="#" style="display:none"></a>
                            <a href="${href}" download>
                            <i class="fa-regular fa-download"></i>
                            Download
                            </a>
                        </div>
                
                        <ul id="about" class="dar-tr_content active">
                            <li><span>Quality:</span><span>${quality || '•••'}</span></li>
                            <li><span>Resolution:</span><span>${r.res || '•••'}</span></li>
                            <li><span>Language:</span><span>${audioSel.label || '•••'}</span></li>
                            <li><span>Subtitles:</span><span>${hasSubtitles ? 'Yes' : 'No'}</span></li>
                        </ul>
                    `;
                    itemsWrap.appendChild(item);

                    const downloadLink = item.querySelector('a[download]');
                    if (downloadLink) {
                        const originalHtml = downloadLink.innerHTML;
                        let isPreparing = false;

                        downloadLink.addEventListener('click', async (e) => {
                            e.preventDefault();
                            if (isPreparing) return;

                            const href = downloadLink.href;
                            if (!href) return;

                            isPreparing = true;
                            downloadLink.innerHTML = `
                                <i class="fa-regular fa-clock"></i>
                                Please wait...
                            `;
                            downloadLink.style.pointerEvents = 'none';

                            try {
                                const response = await fetch(href, {
                                    method: 'HEAD'
                                });

                                if (!response.ok) {
                                    throw new Error(`HTTP ${response.status}`);
                                }

                                setTimeout(() => {
                                    downloadLink.innerHTML = originalHtml;
                                    downloadLink.style.pointerEvents = '';
                                    isPreparing = false;
                                }, 1000);

                                window.location.href = href;
                            } catch (err) {
                                console.error('Download prepare failed:', err);

                                downloadLink.innerHTML = `
                                    <i class="fa-regular fa-circle-exclamation"></i>
                                    Failed
                                `;

                                setTimeout(() => {
                                    downloadLink.innerHTML = originalHtml;
                                    downloadLink.style.pointerEvents = '';
                                    isPreparing = false;
                                }, 3000);
                            }
                        });
                    }
                }
            }

            // ---------- init UI ----------
            show(selSeason.closest('label') || selSeason, isSeries);
            show(selEpisode.closest('label') || selEpisode, isSeries);
            show(selQuality.closest('label') || selQuality, !isSeries);

            clearSelect(selSeason);
            clearSelect(selEpisode);
            clearSelect(selQuality);
            clearSelect(selLang);

            // ---------- state getters ----------
            function getSelectedSeasonIndex() {
                return Math.max(0, parseInt(selSeason.value || '0', 10));
            }
            function getSelectedEpisodeIndex() {
                return Math.max(0, parseInt(selEpisode.value || '0', 10));
            }
            function getSelectedQualityIndex() {
                return Math.max(0, parseInt(selQuality.value || '0', 10));
            }

            function currentFileSet() {
                if (isSeries) {
                    const si = getSelectedSeasonIndex();
                    const ei = getSelectedEpisodeIndex();
                    const season = playlist[si];
                    const ep = season?.folder?.[ei];
                    return {
                        quality: 'WEB-DL',
                        seasonIndex: si,
                        episodeIndex: ei,
                        parsed: parseFileSet(ep?.file || '', ep?.subtitle || '')
                    };
                } else {
                    const qi = getSelectedQualityIndex();
                    const q = playlist[qi];
                    return {
                        quality: q?.title || 'WEB-DL',
                        seasonIndex: null,
                        episodeIndex: null,
                        parsed: parseFileSet(q?.file || '', q?.subtitle || '')
                    };
                }
            }

            // ---------- fill selects ----------
            function fillSeasons() {
                clearSelect(selSeason);
                playlist.forEach((s, i) => addOption(selSeason, String(i), s.title || `Season ${i + 1}`));
                selSeason.value = '0';
            }

            function fillEpisodes() {
                clearSelect(selEpisode);
                const si = getSelectedSeasonIndex();
                const season = playlist[si];
                (season?.folder || []).forEach((ep, i) => addOption(selEpisode, String(i), ep.title || `Episode ${i + 1}`));
                selEpisode.value = '0';
            }

            function fillQualities() {
                clearSelect(selQuality);
                playlist.forEach((q, i) => addOption(selQuality, String(i), q.title || `Quality ${i + 1}`));
                selQuality.value = '0';
            }

            function fillLanguagesFromCurrent() {
                clearSelect(selLang);
                const { parsed } = currentFileSet();
                parsed.languages.forEach((l, i) => addOption(selLang, String(i), l.label));
                selLang.value = parsed.languages.length ? '0' : '';
            }

            async function updateRender() {
                if (!Object.keys(sizeMap).length) {
                    await loadSizeMap();
                }

                const { quality, seasonIndex, episodeIndex, parsed } = currentFileSet();
                const langIndex = Math.max(0, parseInt(selLang.value || '0', 10));
                const audioSel = parsed.languages[langIndex];

                renderItems({
                    quality,
                    base: parsed.base,
                    resolutions: parsed.resolutions,
                    subtitles: parsed.subtitles,
                    audioSel,
                    seasonIndex,
                    episodeIndex
                });
            }

            // ---------- wire ----------
            if (isSeries) {
                fillSeasons();
                fillEpisodes();
                fillLanguagesFromCurrent();
                updateRender();

                selSeason.addEventListener('change', () => {
                    fillEpisodes();
                    fillLanguagesFromCurrent();
                    updateRender();
                });

                selEpisode.addEventListener('change', () => {
                    fillLanguagesFromCurrent();
                    updateRender();
                });

                selLang.addEventListener('change', updateRender);

            } else {
                fillQualities();
                fillLanguagesFromCurrent();
                updateRender();

                selQuality.addEventListener('change', () => {
                    fillLanguagesFromCurrent();
                    updateRender();
                });

                selLang.addEventListener('change', updateRender);
            }
        }
    })();
})();
