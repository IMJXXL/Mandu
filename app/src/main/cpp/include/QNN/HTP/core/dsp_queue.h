// ==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause-Clear
//
// ==============================================================================

#pragma once

#ifdef DEMAND_PAGING

#include <cstdio>
#include <cstring>
#include <inttypes.h>
#include <functional>
#include <utility>

#include "throw_error.h"

// LCOV_EXCL_START [SAFTYSWCCB-1735]
//
// We can convert tests to coverage once the feature is added
//

// DSP buffer information.
struct BufItem {
    uint32_t fd = 0;
    void *ptr = nullptr;

    bool valid() const { return fd > 0; }
};

#if defined(ANDROID) || defined(__hexagon__)
// Use the real dspqueue SDK only when the headers are actually available AND
// we are on a platform that supports it (Hexagon QURT or Android/QCLinux host
// with the SDK linked).  H2 builds and aarch64 builds that don't
// link the dspqueue SDK fall through to the x86-style simulation below.
#if __has_include(<dspqueue.h>) && __has_include(<AEEStdErr.h>) && !defined(USE_OS_H2)

#include <AEEStdErr.h>
#include <dspqueue.h>

// For on-device runs (meaning Android on the host side and Hexagon/QuRT on the NSP side), use the
// DSP packet queue from the Hexagon SDK.

// Pretty simplistic wrapper around the DSP async queues.  This just handles an arbitrary data type
// and a single buffer in either direction.
template <typename T> class DspQueue {
  public:
    using value_type = T;
    struct data_type {
        value_type val;
        BufItem bufitem;
    };
    using cb_type = std::function<void(const data_type &)>;
    using this_type = DspQueue<value_type>;

    // Creates and exports a queue.  Once created, the ID must be transported over to the NSP
    // using a FastRPC call.
    DspQueue(int domain_id, uint32_t req_queue_size, uint32_t resp_queue_size, cb_type callback = cb_type{})
        : m_cb(callback)
    {
        // Due to queue overhead, allocate ~1.5% of what's requested.
        int actual_req_size = static_cast<int>(get_queue_size(sizeof(value_type), req_queue_size));
        int actual_resp_size = static_cast<int>(get_queue_size(sizeof(value_type), resp_queue_size));

        //Debug("DspQueue:  " << actual_req_size << "," << actual_resp_size);

        int err = dspqueue_create(domain_id, 0, actual_req_size, actual_resp_size,
                                  (callback) ? callback_internal : nullptr, error_callback_fatal, this, &m_q);
        if (err != 0) {
            throw_error("dspqueue_create failed: 0x%08x", err);
        }

        err = dspqueue_export(m_q, &m_q_id);
        if (err != 0) {
            throw_error("dspqueue_export failed: 0x%08x", err);
        }
    }

    // This imports a queue.  Use this on the skel (NSP) side.
    DspQueue(uint64_t q_id) : m_q_id(q_id)
    {
        int err = dspqueue_import(m_q_id, nullptr, error_callback_fatal, nullptr, &m_q);
        if (err != 0) {
            throw_error("dspqueue_import failed: 0x%08x", err);
        }
    }

    ~DspQueue() { dspqueue_close(m_q); }

    DspQueue(const DspQueue &) = delete;
    DspQueue &operator=(const DspQueue &) = delete;
    DspQueue(DspQueue &&) = delete;
    DspQueue &operator=(DspQueue &&) = delete;

    uint64_t queue_id() const { return m_q_id; };

    void send(const value_type &v) { send(v, BufItem{}); }

    // Performs a blocking write.
    void send(const value_type &v, const BufItem &buf)
    {
        struct dspqueue_buffer dsp_buf;
        uint32_t num_bufs;
        if (buf.valid()) {
            num_bufs = 1;
            memset(&dsp_buf, 0, sizeof(struct dspqueue_buffer));
            dsp_buf.fd = buf.fd;
            dsp_buf.flags = (DSPQUEUE_BUFFER_FLAG_REF | // Take a reference
                             DSPQUEUE_BUFFER_FLAG_FLUSH_SENDER | // Flush CPU
                             DSPQUEUE_BUFFER_FLAG_INVALIDATE_RECIPIENT); // Invalidate DSP
        } else {
            num_bufs = 0;
        }

        int err = dspqueue_write(m_q, 0, num_bufs, &dsp_buf, sizeof(value_type), (const uint8_t *)&v,
                                 DSPQUEUE_TIMEOUT_NONE);
        if (err != 0) {
            throw_error("dspqueue_write failed: 0x%08x", err);
        }
    }

    // Perform a blocking read of the queue. Returns the value and buffer info, if relevant (file
    // descriptor and pointer).
    data_type recv()
    {
        value_type v;
        uint32_t flags;
        uint32_t num_bufs;
        uint32_t msg_len;
        struct dspqueue_buffer dsp_buf;

        int err = dspqueue_read(m_q, &flags, 1, &num_bufs, &dsp_buf, sizeof(value_type), &msg_len, (uint8_t *)&v,
                                DSPQUEUE_TIMEOUT_NONE);
        if (err != 0) {
            throw_error("dspqueue_read failed: 0x%08x", err);
        }
        if (msg_len != sizeof(value_type)) {
            throw_error("dspqueue_read returned an invalid message size.");
        }
        if (num_bufs == 0) {
            dsp_buf.fd = 0;
            dsp_buf.ptr = nullptr;
        }
        return data_type{v, BufItem{dsp_buf.fd, dsp_buf.ptr}};
    }

    bool recv_noblock(data_type &data)
    {
        uint32_t flags;
        uint32_t num_bufs;
        uint32_t msg_len;
        struct dspqueue_buffer dsp_buf;

        int err = dspqueue_read_noblock(m_q, &flags, 1, &num_bufs, &dsp_buf, sizeof(value_type), &msg_len,
                                        (uint8_t *)&data.val);
        if (err == AEE_EWOULDBLOCK) {
            return false;
        }
        if (err != 0) {
            throw_error("dspqueue_read_noblock failed: 0x%08x", err);
        }
        if (msg_len != sizeof(value_type)) {
            throw_error("dspqueue_read returned an invalid message size.");
        }
        if (num_bufs == 0) {
            dsp_buf.fd = 0;
            dsp_buf.ptr = nullptr;
        }
        data.bufitem.fd = dsp_buf.fd;
        data.bufitem.ptr = dsp_buf.ptr;

        return true;
    }

  protected:
    static constexpr size_t Alignment = 8;

    // Get the size of the request or response queue (in bytes) based upon the element size.  We add
    // in some overhead (a 1.5x factor) and space for one buffer along with each message element.
    // We also have to round up to the nearest 8 bytes.
    static size_t get_queue_size(size_t elm_size, unsigned qsize)
    {
        size_t sz = static_cast<size_t>((elm_size + sizeof(dspqueue_buffer)) * qsize * 1.5f);
        if (sz % Alignment) {
            sz += (Alignment - (sz % Alignment));
        }
        return sz;
    }

    static void callback_internal(dspqueue_t queue, int err, void *ctx)
    {
        this_type *self = reinterpret_cast<this_type *>(ctx);
        this_type::data_type data;

        while (true) {
            if (!self->recv_noblock(data)) {
                break;
            }
            self->m_cb(data);
        }
    }

    static void error_callback_fatal(dspqueue_t queue, AEEResult error, void *context)
    {
        throw_error("ERROR 0x%x: for queue %pK\n", error, (void *)queue);
    }

    uint64_t m_q_id;
    dspqueue_t m_q;
    cb_type m_cb;
};

#define HAS_DSP_QUEUE 1
#endif // dspqueue header check
#else

#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <condition_variable>

class DspQueueBase;

using DspQueueMap = std::map<uint64_t, DspQueueBase *>;

uint64_t register_queue(DspQueueBase *);
void remove_queue(uint64_t id) noexcept;
DspQueueBase *find_shared_base_queue(uint64_t id);

// If running on x86, then we use a simple queue using standard containers and condition variables.
//
// This is the base type (not type specific).
class DspQueueBase {
  public:
    DspQueueBase() { m_q_id = register_queue(this); }

    virtual ~DspQueueBase() { remove_queue(m_q_id); }

    uint64_t id() const { return m_q_id; }

  private:
    uint64_t m_q_id = 0;
};

// Note that we're not modeling a queue of a limited size, so we can always send.
template <typename T> class DspQueueImpl : public DspQueueBase, public std::enable_shared_from_this<DspQueueImpl<T>> {
  public:
    using value_type = T;
    struct data_type {
        value_type val;
        BufItem bufitem;
    };
    using cb_type = std::function<void(const data_type &)>;
    using this_type = DspQueueImpl<T>;

    DspQueueImpl(cb_type callback) : m_cb(callback) {}

    void send(data_type &&data, bool dsp_side)
    {
        if (dsp_side) {
            // A send, from the DSP, is just handled immediately by the receive callback.
            m_cb(data);
        } else {
            std::unique_lock lk{m_mtx};

            m_q.emplace_back(data);

            lk.unlock();
            m_data_avail.notify_one();
        }
    }

    data_type recv()
    {
        std::unique_lock lk{m_mtx};

        m_data_avail.wait(lk, [this] { return !m_q.empty(); });

        auto x = m_q.front();
        m_q.pop_front();

        return x;
    }

    bool recv_noblock(data_type &data)
    {
        std::unique_lock lk{m_mtx};

        if (m_q.empty()) return false;

        data = m_q.front();
        m_q.pop_front();

        return true;
    }

    std::shared_ptr<this_type> get_this() { return this->shared_from_this(); }

  private:
    std::mutex m_mtx;
    std::condition_variable m_data_avail;

    std::deque<data_type> m_q;
    cb_type m_cb;
};

template <typename T> inline std::shared_ptr<DspQueueImpl<T>> find_shared_queue(uint64_t q_id)
{
    DspQueueImpl<T> *q = reinterpret_cast<DspQueueImpl<T> *>(find_shared_base_queue(q_id));
    if (!q) {
        throw_error("Shared queue (id=%" PRIu64 ") not found.", q_id);
    }

    return q->get_this();
}

// This is the outer wrapper for the queue.
template <typename T> class DspQueue {
  public:
    using value_type = T;
    using queue_type = DspQueueImpl<T>;
    using data_type = typename queue_type::data_type;
    using cb_type = std::function<void(const data_type &)>;
    using this_type = DspQueue<value_type>;

    // Creates and exports a queue.  Once created, the ID must be transported over to the NSP
    // using a FastRPC call.
    DspQueue(int, uint32_t, uint32_t, cb_type callback = cb_type{})
        : m_q(std::make_shared<queue_type>(callback)), m_dsp_side(false)
    {
    }

    // This imports a queue.  Use this on the skel (NSP) side.
    DspQueue(uint64_t q_id) : m_q(find_shared_queue<value_type>(q_id)), m_dsp_side(true) {}

    ~DspQueue() {}

    uint64_t queue_id() const { return m_q->id(); }

    void send(const value_type &v) { send(v, BufItem{}); }

    // Performs a blocking write.
    void send(const value_type &v, const BufItem &buf) { m_q->send(data_type{v, buf}, m_dsp_side); }

    // Perform a blocking read of the queue. Returns the value and buffer info, if relevant (file
    // descriptor and pointer).
    data_type recv() { return m_q->recv(); }

    bool recv_noblock(data_type &data) { return m_q->recv_noblock(data); }

  private:
    std::shared_ptr<queue_type> m_q;
    bool m_dsp_side;
};

#define HAS_DSP_QUEUE 1
#endif

#ifdef HAS_DSP_QUEUE

using dma_va_tag = uint32_t;

inline static constexpr dma_va_tag UNMPPED = 0;
inline static constexpr dma_va_tag VAMISS = 1;
inline static constexpr dma_va_tag MAP = 2;
inline static constexpr dma_va_tag UNMAP = 3;

// Message payload for the DMA virtual-address queue.
struct DspQueueMsg {
    dma_va_tag tag;
    uint64_t vva;
    uint64_t va;
};

using UInt64Queue = DspQueue<DspQueueMsg>;

// Print a single DMA log entry: address and VA tag.
inline void print_dma_log_entry(const UInt64Queue::value_type &entry)
{
    static const char *tag_names[] = {"UNMPPED", "VAMISS", "MAP", "UNMAP"};
    const uint32_t t = entry.tag;
    const char *tag_str = (t < 4) ? tag_names[t] : "UNKNOWN";
    printf("DMA src: 0x%016" PRIx64 " va: 0x%016" PRIx64 " [%s (%" PRIu32 ")]\n", entry.vva, entry.va, tag_str, t);
}

#endif // HAS_DSP_QUEUE

#endif // DEMAND_PAGING

// LCOV_EXCL_STOP
