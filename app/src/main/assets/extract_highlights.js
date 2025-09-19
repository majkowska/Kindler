(function() {
    const urlParams = new URLSearchParams(window.location.search);
    const asin = urlParams.get('asin');
    let collectedAnnotations = [];

    // Select all individual annotation entries/blocks under the main annotations container
    const annotationEntries = document.querySelectorAll('div#kp-notebook-annotations > div.a-row.a-spacing-base');

    annotationEntries.forEach(entry => {
        const highlightSpan = entry.querySelector('span#highlight');

        if (highlightSpan) {
            const highlightText = highlightSpan.innerText.trim();
            let noteText = ''; // Default to empty string if no note

            // Look for a note within the same annotation entry
            const noteSpan = entry.querySelector('span#note');
            if (noteSpan) {
                noteText = noteSpan.innerText.trim();
            }

            collectedAnnotations.push({
                highlight: highlightText,
                note: noteText
            });
        }
        // If no highlightSpan in the entry, this entry is ignored (e.g., an orphaned note without a highlight).
    });

    if (typeof AndroidInterface !== 'undefined' && AndroidInterface.processBookHighlights) {
        AndroidInterface.processBookHighlights(JSON.stringify(collectedAnnotations), asin);
    } else {
        console.error("AndroidInterface.processBookHighlights not found.");
        // console.log("ASIN: " + asin + ", Highlights: " + JSON.stringify(collectedAnnotations)); // For browser debugging
    }
})();