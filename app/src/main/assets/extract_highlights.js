(function() {
    function extractAnnotationsFromEntries(annotationEntries) {
        let collectedAnnotations = [];
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
        return collectedAnnotations;
    }

    const urlParams = new URLSearchParams(window.location.search);

    // Select all individual annotation entries/blocks under the main annotations container
    const annotationEntries = document.querySelectorAll('div#kp-notebook-annotations > div.a-row.a-spacing-base');
    let collectedAnnotations = extractAnnotationsFromEntries(annotationEntries);

    if (typeof AndroidInterface !== 'undefined' && AndroidInterface.processBookHighlights) {
        AndroidInterface.processBookHighlights(JSON.stringify(collectedAnnotations));
    } else {
        console.error("AndroidInterface.processBookHighlights not found.");
    }
})();