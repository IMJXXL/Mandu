// ==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause-Clear
//
// ==============================================================================

// This file contains deprecated QHPI functionality to support existing QHPI operators in QNN. It
// should be empty once those operators are updated to use the current version of QHPI.

#ifndef QHPI_QHPI_COMPAT
#define QHPI_QHPI_COMPAT 1

#include "qhpi.h"

#define DEFAULT_PACKAGE "q"

#ifdef __cplusplus
extern "C" {
#endif

enum {
    QHPI_Success = 0, /**< Operation completed successfully. */
    QHPI_Unsupported = 1, /**< Requested feature or option is not supported. */
    QHPI_ErrorFatal = 2, /**< A fatal error occurred. */
    QHPI_RegistrationWarning = 3, /**< Registration succeeded with warnings. */
    QHPI_RegistrationError = 4, /**< Registration failed. */
};

enum {
    QHPI_QUInt8 = 1, /**< Quantized unsigned 8-bit integer. */
    QHPI_QUInt16 = 2, /**< Quantized unsigned 16-bit integer. */
    QHPI_QInt16 = 3, /**< Quantized signed 16-bit integer. */
    QHPI_Float32 = 4, /**< IEEE 754 32-bit float. */
    QHPI_Int32 = 5, /**< Signed 32-bit integer. */
    QHPI_QInt32 = 6, /**< Quantized signed 32-bit integer. */
    QHPI_QInt8 = 7, /**< Quantized signed 8-bit integer. */
    QHPI_Float16 = 8, /**< IEEE 754 16-bit float. */
    QHPI_Int64 = 9, /**< Signed 64-bit integer. */
    QHPI_Any_Element_Type = 240, /**< Wildcard matching any element type (for signatures). */
    QHPI_PrepareError = 241, /**< Pseudo-type indicating a preparation error. */
};

enum {
    QHPI_Layout_Any = 0, /**< No layout constraint (accepts any layout). */
    QHPI_Layout_ShapeOnly = 1, /**< Shape metadata only, no data storage. */

    QHPI_Layout_Flat4 = 2, /**< 4D flat row-major layout. */
    QHPI_Layout_Flat5 = 3, /**< 5D flat row-major layout. */
    QHPI_Layout_Flat6 = 4, /**< 6D flat row-major layout. */

    QHPI_Layout_Singular = 5, /**< Scalar (single element). */
    QHPI_Layout_NCHW = 6, /**< NCHW channel-first layout. */
    QHPI_Layout_Depth32 = 7, /**< Depth-32 blocked layout. */
    QHPI_Layout_Weights8x4 = 8, /**< 8x4 weight packing layout. */

    QHPI_Layout_Crouton_8 = 9, /**< 8-bit crouton (2 KB blocked) layout. */
    QHPI_Layout_Crouton_16 = 10, /**< 16-bit crouton layout. */
    QHPI_Layout_Crouton_32 = 11, /**< 32-bit crouton layout. */
    QHPI_Layout_Crouton4x1 = 12, /**< 4x1 crouton variant. */
    QHPI_Layout_Crouton2x2 = 13, /**< 2x2 crouton variant. */

    QHPI_Layout_WideCrouton_8 = 14, /**< 8-bit wide crouton layout. */
    QHPI_Layout_WideCrouton_32 = 15, /**< 32-bit wide crouton layout. */
    QHPI_Layout_WideCrouton2x2 = 16, /**< 2x2 wide crouton variant. */

    QHPI_Layout_Custom = 1000, /**< Base value for plugin-defined custom layouts. */
    QHPI_Layout_Invalid = INT_MAX /**< Sentinel for invalid layout. */
};

inline QHPI_StatusCode qhpi_standard_layout(QHPI_Standard_Layout standard_layout,
                                            QHPI_ChunkedMemoryLayout *chunked_layout, uint32_t size)
{
    return qhpi_chunked_layout(standard_layout, chunked_layout, size);
}

enum {
    QHPI_MemLoc_DDR_Only = 0, /**< Tensor must reside in DDR. */
    QHPI_MemLoc_TCM_Only = 1, /**< Tensor must reside in TCM. */
    QHPI_MemLoc_DDR_OR_TCM = 2, /**< Tensor may reside in either DDR or TCM. */
    QHPI_MemLoc_Invalid = INT_MAX /**< Sentinel for invalid placement. */
};

enum {
    QHPI_Storage_Direct = 0, /**< Data is contiguous; access via raw pointer. */
    QHPI_Storage_Indirect = 1, /**< Data is in blocks; access via block table. */
    QHPI_Storage_Direct_OR_Indirect = 2 /**< Either storage mode is acceptable. */
};

typedef uint32_t QHPI_RESOURCE;

#ifdef __cplusplus
}
#endif
#endif // #ifndef QHPI_QHPI_COMPAT
