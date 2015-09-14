all: build/Makefile
	make -C build

clean:
	rm -rf build

build:
	mkdir build

build/Makefile: CMakeLists.txt rts/CMakeLists.txt build
	(cd build && cmake ..)

.PHONY: all clean
