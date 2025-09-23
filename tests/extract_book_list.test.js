const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const scriptSource = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'extract_book_list.js'),
  'utf8'
);

// Keep the test expectations aligned with the script's "maxAttempts" equivalent
// configuration so changes to the asset don't silently break the retry coverage.
const MAX_IDLE_ATTEMPTS = (() => {
  const match = scriptSource.match(
    /var maxConsecutiveIdleChecksWithoutNewData\s*=\s*(\d+);/
  );
  if (!match) {
    throw new Error(
      'Unable to locate the maxConsecutiveIdleChecksWithoutNewData constant in extract_book_list.js'
    );
  }
  return Number.parseInt(match[1], 10);
})();

function createBookElement({ asin, titleText, authorText }) {
  const titleElement = titleText === null ? null : { innerText: titleText };
  const authorElement = authorText === null ? null : { innerText: authorText };

  return {
    id: asin,
    querySelector(selector) {
      if (selector === 'h2.kp-notebook-searchable') {
        return titleElement;
      }
      if (selector === 'p.kp-notebook-searchable') {
        return authorElement;
      }
      return null;
    }
  };
}

function createTimerController() {
  let nextId = 1;
  const timeouts = new Map();
  const intervals = new Map();

  function setTimeoutStub(callback, delay) {
    const id = nextId++;
    timeouts.set(id, { callback, delay });
    return id;
  }

  function clearTimeoutStub(id) {
    timeouts.delete(id);
  }

  function setIntervalStub(callback, delay) {
    const id = nextId++;
    intervals.set(id, { callback, delay });
    return id;
  }

  function clearIntervalStub(id) {
    intervals.delete(id);
  }

  function runTimeout(id) {
    const entry = timeouts.get(id);
    if (!entry) {
      return false;
    }
    timeouts.delete(id);
    entry.callback();
    return true;
  }

  function runNextTimeout() {
    for (const [id] of timeouts) {
      runTimeout(id);
      return true;
    }
    return false;
  }

  function getTimeoutIds() {
    return Array.from(timeouts.keys());
  }

  function getIntervalIds() {
    return Array.from(intervals.keys());
  }

  return {
    setTimeout: setTimeoutStub,
    clearTimeout: clearTimeoutStub,
    setInterval: setIntervalStub,
    clearInterval: clearIntervalStub,
    runTimeout,
    runNextTimeout,
    getTimeoutIds,
    getIntervalIds
  };
}

function createMutationObserverHarness() {
  const instances = [];

  function MutationObserver(callback) {
    this.callback = callback;
    this.observeCalls = [];
    this.disconnectCalls = 0;
    instances.push(this);
  }

  MutationObserver.prototype.observe = function(target, options) {
    this.observeCalls.push({ target, options });
    this.observedTarget = target;
    this.observedOptions = options;
  };

  MutationObserver.prototype.disconnect = function() {
    this.disconnectCalls += 1;
  };

  MutationObserver.prototype.trigger = function(mutations) {
    this.callback(mutations, this);
  };

  return { MutationObserver, instances };
}

function runScript({
  libraryExists = true,
  books = [],
  androidInterface,
  mutationObserver
} = {}) {
  const dateElements = new Map();
  const bookElements = books.map((book) => {
    if (book.dateValue !== undefined) {
      dateElements.set(`kp-notebook-annotated-date-${book.asin}`, { value: book.dateValue });
    }
    return createBookElement(book);
  });

  const libraryDiv = libraryExists ? {} : null;

  const timerController = createTimerController();
  const scrollCalls = [];

  const document = {
    body: { scrollHeight: 1000 },
    getElementById(id) {
      if (id === 'kp-notebook-library') {
        return libraryDiv;
      }
      return dateElements.get(id) || null;
    },
    querySelectorAll(selector) {
      if (selector === '.kp-notebook-library-each-book') {
        return bookElements.slice();
      }
      return [];
    }
  };

  const logs = [];
  const errors = [];

  const windowObject = {
    scrollTo: (...args) => {
      scrollCalls.push(args);
    }
  };
  windowObject.document = document;

  const context = {
    window: windowObject,
    document,
    console: {
      log: (...args) => logs.push(args.join(' ')),
      error: (...args) => errors.push(args.join(' '))
    },
    setTimeout: timerController.setTimeout,
    clearTimeout: timerController.clearTimeout,
    setInterval: timerController.setInterval,
    clearInterval: timerController.clearInterval,
    JSON
  };

  if (androidInterface !== undefined) {
    context.AndroidInterface = androidInterface;
  }
  if (mutationObserver !== undefined) {
    const observerValue =
      typeof mutationObserver === 'function'
        ? mutationObserver
        : mutationObserver.MutationObserver;
    context.MutationObserver = observerValue;
  }

  const vmContext = vm.createContext(context);
  vm.runInContext(scriptSource, vmContext);

  return {
    logs,
    errors,
    timerController,
    scrollCalls,
    mutationObserver
  };
}

test('extracts complete books and sends them to Android interface', () => {
  const capturedPayloads = [];
  const androidInterface = {
    processBookData(payload) {
      capturedPayloads.push(JSON.parse(payload));
    }
  };

  runScript({
    books: [
      {
        asin: 'asin123',
        titleText: 'Example Title',
        authorText: 'By: Example Author',
        dateValue: '2024-01-01'
      }
    ],
    androidInterface
  });

  assert.equal(capturedPayloads.length, 1);
  assert.deepEqual(capturedPayloads[0], [
    {
      asin: 'asin123',
      title: 'Example Title',
      author: 'Example Author',
      lastAccessedDate: '2024-01-01'
    }
  ]);
});

test('omits books that are missing required data', () => {
  let called = false;
  const androidInterface = {
    processBookData() {
      called = true;
    }
  };

  runScript({
    books: [
      {
        asin: 'asin456',
        titleText: 'Example Title',
        authorText: 'By: ',
        dateValue: '2024-02-02'
      }
    ],
    androidInterface
  });

  assert.equal(called, false);
});

test('returns early when the library container is missing', () => {
  let called = false;
  const androidInterface = {
    processBookData() {
      called = true;
    }
  };

  const result = runScript({ libraryExists: false, androidInterface });

  assert.equal(called, false);
  assert.deepEqual(result.timerController.getTimeoutIds(), []);
  assert.deepEqual(result.timerController.getIntervalIds(), []);
});

test('mutation observer resets idle timer when new nodes appear', () => {
  const mutationObserver = createMutationObserverHarness();
  const capturedPayloads = [];
  const androidInterface = {
    processBookData(payload) {
      capturedPayloads.push(JSON.parse(payload));
    }
  };

  const result = runScript({
    books: [
      {
        asin: 'asin789',
        titleText: 'Another Title',
        authorText: 'By: Another Author',
        dateValue: '2024-03-03'
      }
    ],
    androidInterface,
    mutationObserver
  });

  assert.equal(mutationObserver.instances.length, 1);
  const [observerInstance] = mutationObserver.instances;

  const initialTimeoutIds = result.timerController.getTimeoutIds();
  assert.equal(initialTimeoutIds.length, 1);
  const initialTimeoutId = initialTimeoutIds[0];

  observerInstance.trigger([{ type: 'childList', addedNodes: [{}] }]);

  assert.equal(result.timerController.getTimeoutIds().includes(initialTimeoutId), false);
  assert.equal(result.timerController.getTimeoutIds().length, 1);
  assert.equal(result.timerController.getIntervalIds().length, 1);
  assert.equal(observerInstance.disconnectCalls, 0);

  const ranTimeout = result.timerController.runNextTimeout();
  assert.equal(ranTimeout, true);
  assert.equal(capturedPayloads.length, 0);
  assert.equal(result.timerController.getTimeoutIds().length, 1);
});

test('extracts after the idle attempt limit is reached', () => {
  const mutationObserver = createMutationObserverHarness();
  const capturedPayloads = [];
  const androidInterface = {
    processBookData(payload) {
      capturedPayloads.push(JSON.parse(payload));
    }
  };

  const result = runScript({
    books: [
      {
        asin: 'asin123',
        titleText: 'Example Title',
        authorText: 'By: Example Author',
        dateValue: '2024-01-01'
      }
    ],
    androidInterface,
    mutationObserver
  });

  assert.equal(mutationObserver.instances.length, 1);
  assert.equal(result.timerController.getIntervalIds().length, 1);

  const attemptsBeforeExtraction = Math.max(0, MAX_IDLE_ATTEMPTS - 1);

  for (let attempt = 0; attempt < attemptsBeforeExtraction; attempt += 1) {
    const ranTimeout = result.timerController.runNextTimeout();
    assert.equal(ranTimeout, true);
    assert.equal(capturedPayloads.length, 0);
    assert.equal(result.timerController.getTimeoutIds().length, 1);
  }

  const finalTimeoutRan = result.timerController.runNextTimeout();
  assert.equal(finalTimeoutRan, true);
  assert.equal(capturedPayloads.length, 1);
  assert.deepEqual(capturedPayloads[0], [
    {
      asin: 'asin123',
      title: 'Example Title',
      author: 'Example Author',
      lastAccessedDate: '2024-01-01'
    }
  ]);

  assert.deepEqual(result.timerController.getTimeoutIds(), []);
  assert.deepEqual(result.timerController.getIntervalIds(), []);

  const [observerInstance] = mutationObserver.instances;
  assert.equal(observerInstance.disconnectCalls, 1);
});
