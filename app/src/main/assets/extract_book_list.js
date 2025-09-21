(function() {
    var scrollIntervalMs = 2000; // How often to trigger a scroll to fetch more content
    var idleDelayMs = scrollIntervalMs; // Keep idle checks aligned with scroll cadence
    var maxConsecutiveIdleChecksWithoutNewData = 3; // Matches the previous "maxAttempts" behavior
    var observer = null;
    var idleTimer = null;
    var scrollTimer = null;
    var sawNewNodesSinceLastIdleCheck = false;
    var consecutiveIdleChecksWithoutNewData = 0;

    function extractBookData() {
        var libraryDiv = document.getElementById('kp-notebook-library');
        if (libraryDiv) {
            var books = [];
            var bookElements = document.querySelectorAll('.kp-notebook-library-each-book');
            bookElements.forEach(function(bookElement) {
                var asin = bookElement.id;
                var titleElement = bookElement.querySelector('h2.kp-notebook-searchable');
                var authorElement = bookElement.querySelector('p.kp-notebook-searchable');
                var dateElement = document.getElementById('kp-notebook-annotated-date-' + asin);

                var title = titleElement ? titleElement.innerText : '';
                var author = authorElement ? authorElement.innerText.replace('By: ', '') : '';
                var lastAccessedDate = dateElement ? dateElement.value : '';

                if (asin && title.trim() && author.trim() && lastAccessedDate.trim()) {
                    books.push({
                        asin: asin,
                        title: title,
                        author: author,
                        lastAccessedDate: lastAccessedDate
                    });
                }
            });
            if (books.length > 0) {
                if (typeof AndroidInterface !== 'undefined' && AndroidInterface.processBookData) {
                    AndroidInterface.processBookData(JSON.stringify(books));
                } else {
                    console.error("AndroidInterface not found or processBookData is not a function.");
                }
            } else {
                console.log("No books found after scrolling.");
            }
        } else {
            console.log("Library div not found after scrolling.");
        }
    }

    function cleanupAndExtract() {
        if (observer) {
            observer.disconnect();
            observer = null;
        }
        if (idleTimer) {
            clearTimeout(idleTimer);
            idleTimer = null;
        }
        if (scrollTimer) {
            clearInterval(scrollTimer);
            scrollTimer = null;
        }
        console.log("No new nodes detected within the idle window. Extracting data.");
        extractBookData();
    }

    function scheduleIdleCheck() {
        if (idleTimer) {
            clearTimeout(idleTimer);
        }
        idleTimer = setTimeout(handleIdleTimeout, idleDelayMs);
    }

    function handleIdleTimeout() {
        if (sawNewNodesSinceLastIdleCheck) {
            sawNewNodesSinceLastIdleCheck = false;
            consecutiveIdleChecksWithoutNewData = 0;
            scheduleIdleCheck();
            return;
        }

        consecutiveIdleChecksWithoutNewData += 1;

        if (consecutiveIdleChecksWithoutNewData < maxConsecutiveIdleChecksWithoutNewData) {
            console.log(
                "No new nodes detected during idle window. Allowing additional attempt " +
                consecutiveIdleChecksWithoutNewData +
                " of " +
                maxConsecutiveIdleChecksWithoutNewData +
                "."
            );
            scheduleIdleCheck();
            return;
        }

        console.log("Reached idle attempt limit without new nodes. Extracting data.");
        cleanupAndExtract();
    }

    function registerNewNodesDetected() {
        sawNewNodesSinceLastIdleCheck = true;
        consecutiveIdleChecksWithoutNewData = 0;
        scheduleIdleCheck();
    }

    function startObserver(libraryDiv) {
        observer = new MutationObserver(function(mutationsList) {
            var sawAddedNodes = false;
            for (var i = 0; i < mutationsList.length; i++) {
                var mutation = mutationsList[i];
                if (mutation.type === 'childList' && mutation.addedNodes && mutation.addedNodes.length > 0) {
                    sawAddedNodes = true;
                    break;
                }
            }

            if (sawAddedNodes) {
                console.log("Detected new nodes in the library. Waiting for additional content before final extraction.");
                registerNewNodesDetected();
            }
        });

        observer.observe(libraryDiv, { childList: true, subtree: true });
    }

    function startScrolling() {
        window.scrollTo(0, document.body.scrollHeight);
        scrollTimer = setInterval(function() {
            window.scrollTo(0, document.body.scrollHeight);
        }, scrollIntervalMs);
    }

    var libraryDiv = document.getElementById('kp-notebook-library');

    if (!libraryDiv) {
        console.log("Library div not found. Data extraction will not proceed.");
        return;
    }

    if (typeof MutationObserver === 'undefined') {
        console.log("MutationObserver is not supported in this environment. Extracting data immediately.");
        extractBookData();
        return;
    }

    console.log("Library div found. Setting up mutation observer to watch for new content.");

    startObserver(libraryDiv);
    startScrolling();
    scheduleIdleCheck();
})();
