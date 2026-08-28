package ycsb

import (
	"math/rand/v2"
	"strconv"
	"strings"
)

const asciiPoolSize = 16384

var asciiPool string

func init() {
	const chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	buf := make([]byte, asciiPoolSize)
	for i := 0; i < asciiPoolSize; i++ {
		buf[i] = chars[i%len(chars)]
	}
	asciiPool = string(buf)
}

// BuildKeyName formats an integer key into the standard YCSB zero-padded primary key string (e.g. user000000000001).
// If zeroPadding is <= 0 or the string representation is already longer than zeroPadding, the key is unpadded (e.g. user42).
func BuildKeyName(keyNumber int64, zeroPadding int) string {
	keyStr := strconv.FormatInt(keyNumber, 10)
	if zeroPadding <= 0 || len(keyStr) >= zeroPadding {
		return "user" + keyStr
	}
	paddingZeros := zeroPadding - len(keyStr)
	var builder strings.Builder
	builder.Grow(4 + zeroPadding)
	builder.WriteString("user")
	for i := 0; i < paddingZeros; i++ {
		builder.WriteByte('0')
	}
	builder.WriteString(keyStr)
	return builder.String()
}

// GenerateRandomString generates an ASCII printable alphanumeric string of the specified length in O(1) time
// by slicing into a precomputed 16 KB static ASCII character pool.
func GenerateRandomString(length int) string {
	if length <= 0 {
		return ""
	}
	if length <= asciiPoolSize {
		maxOffset := asciiPoolSize - length
		offset := 0
		if maxOffset > 0 {
			offset = rand.IntN(maxOffset)
		}
		return asciiPool[offset : offset+length]
	}

	var builder strings.Builder
	builder.Grow(length)
	offset := 0
	for offset < length {
		chunkSize := asciiPoolSize
		if length-offset < chunkSize {
			chunkSize = length - offset
		}
		maxOffset := asciiPoolSize - chunkSize
		poolOffset := 0
		if maxOffset > 0 {
			poolOffset = rand.IntN(maxOffset)
		}
		builder.WriteString(asciiPool[poolOffset : poolOffset+chunkSize])
		offset += chunkSize
	}
	return builder.String()
}
