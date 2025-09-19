(function() {
    var previousHeight = 0;
    var currentHeight = document.body.scrollHeight;
    var attempts = 0;
    var maxAttempts = 5; // Number of times to try scrolling to ensure all content is loaded
    var scrollDelay = 2000; // Delay in ms to wait for content to load after scroll

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

    function scrollAndCheck() {
        previousHeight = document.body.scrollHeight;
        window.scrollTo(0, document.body.scrollHeight);
        
        setTimeout(function() {
            currentHeight = document.body.scrollHeight;
            if (currentHeight > previousHeight) {
                console.log("Scrolled. New height: " + currentHeight + ", Previous height: " + previousHeight + ", Attempt: " + attempts);
                attempts = 0;
                scrollAndCheck();
            } else if (attempts < maxAttempts) {
                attempts++;
                scrollAndCheck();
                console.log("Failed scroll attempt. Current height: " + currentHeight + ", Previous height: " + previousHeight + ", Attempt: " + attempts);}
            else {
                console.log("Finished scrolling, attempts: " + attempts + ". Current height: " + currentHeight + ", Previous height: " + previousHeight + ". Extracting data.");
                extractBookData();
            }
        }, scrollDelay);
    }

    if (document.getElementById('kp-notebook-library')) {
        console.log("Initial library div found. Starting scroll and check.");
        scrollAndCheck();
    } else {
        console.log("Initial library div not found. Data extraction will not proceed.");
    }
})();
