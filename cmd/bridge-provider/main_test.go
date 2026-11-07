package main

import (
	"strings"
	"testing"
)

func TestCappedBuffer(t *testing.T) {
	const max = 16
	cases := []struct {
		name      string
		tail      bool
		writes    []string
		want      string
		truncated bool
	}{
		{"under", false, []string{"abc"}, "abc", false},
		{"exact fit", false, []string{"0123456789abcdef"}, "0123456789abcdef", false},
		{"head over boundary", false, []string{"aaaaaaaaaaaaaaaa", "bb", "c"}, strings.Repeat("a", 16), true},
		{"head mid-write cut", false, []string{"12345678901234", "5678"}, "1234567890123456", true},
		{"tail", true, []string{"a", strings.Repeat("b", 16), "cc"}, strings.Repeat("b", 14) + "cc", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b := &cappedBuffer{max: max, tail: tc.tail}
			for _, w := range tc.writes {
				n, err := b.Write([]byte(w))
				if err != nil || n != len(w) {
					t.Fatalf("Write(%d bytes) = (%d, %v); want (%d, nil)", len(w), n, err, len(w))
				}
			}
			if got := b.String(); got != tc.want {
				t.Fatalf("String() = %q; want %q", got, tc.want)
			}
			if b.truncated != tc.truncated {
				t.Fatalf("truncated = %v; want %v", b.truncated, tc.truncated)
			}
		})
	}
}
