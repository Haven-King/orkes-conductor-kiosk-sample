import http from 'k6/http';
import { check, sleep } from 'k6';

const CONDUCTOR_SERVER_URL = 'https://perf-test-xww.orkesconductor.net';
const TOKEN =  ; // Token goes here.

export const options = {
    discardResponseBodies: true,
    scenarios: {
        contacts: {
            executor: 'constant-arrival-rate',

            // How long the test lasts
            duration: '120s',

            // How many iterations per timeUnit
            rate: 50,

            // Start `rate` iterations per second
            timeUnit: '1s',

            // Pre-allocate 10 VUs before starting the test
            preAllocatedVUs: 10000,

            // Spin up a maximum of 50 VUs to sustain the defined
            // constant arrival rate.
            maxVUs: 10000,
        },
    },
};

export default function () {
    // Define the request URL
    const start = `${CONDUCTOR_SERVER_URL}/api/workflow/execute/KioskOrder/1?waitForSeconds=5&returnStrategy=BLOCKING_TASK_INPUT&consistency=SYNCHRONOUS`;

    // Define the request body as a JSON object
    const payload = JSON.stringify({
        name: "KioskOrder",
        version: "1",
        correlationId: `request-${__ITER}`, // Unique correlationId using the iteration number
        input: {
            uri: "http://172.20.38.11/",
            method: "GET",
        }
    });

    const payload2 = JSON.stringify({
        action: "AddItem"
    });

    const payload3 = JSON.stringify({
        action: "Checkout"
    });

    // Define the request headers, including Authorization
    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Accept': '*/*',
            'X-Authorization': TOKEN,
            'Accept-Encoding': 'gzip'
        },
        tags: {
            'name': 'grpc'
        }
    };

    // Send the POST request
    const r = http.post(start, payload, params);

    // Check the response status to ensure it's 200 OK
    check(r, {
        'Workflow Started': (r) => r.status === 200,
        'Workflow ID present': (r) => r.headers["Workflowid"] != null,
    });

    let workflowId =r.headers["Workflowid"];

    let addItem = `https://perf-test-xww.orkesconductor.net/api/tasks/${workflowId}/COMPLETED/signal/sync?returnStrategy=BLOCKING_TASK_INPUT`;

    const addItemRes = http.post(addItem, payload2, params);

    check(addItemRes, {
        'AddItem call #1': (addItemRes) => addItemRes.status === 200
    });

    sleep(1);

    const addItemRes2 = http.post(addItem, payload2, params);

    check(addItemRes2, {
        'AddItem Call #2': (addItemRes2) => addItemRes2.status === 200
    });

    sleep(1);

    const checkoutRes = http.post(addItem, payload3, params);

    check(checkoutRes, {
        'Checkout': (checkoutRes) => checkoutRes.status === 200
    });

}