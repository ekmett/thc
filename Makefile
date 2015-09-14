all: build/Makefile
	make -C build

doc: build/Makefile
	make -C build doc
	open build/html/annotated.html

clean:
	rm -rf build

build:
	mkdir build

build/Makefile: CMakeLists.txt rts/CMakeLists.txt tests/CMakeLists.txt build
	(cd build && cmake ..)

.PHONY: all clean
