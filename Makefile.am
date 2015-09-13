REPOSITORY_HTTP = http://github.com/ekmett/thc

ACLOCAL_AMFLAGS = -I m4

AM_CPPFLAGS = $(PTHREAD_CPPFLAGS)
AM_LDFLAGS = $(PTHREAD_CPPFLAGS) -L/usr/local/lib

# scripts for maintainer use
noinst_SCRIPTS=bin/autogen.sh

distdir=$(top_srcdir)/dist/thc-$(VERSION)

# install the documentation
docdir = $(prefix)/share/doc/thc-$(VERSION)
dist_doc_DATA = COPYING INSTALL README

nobase_noinst_HEADERS = \
	rts/config.h \
	rts/gc.h

nobase_include_HEADERS = \
        rts/gc.h \

lib_LTLIBRARIES = libthc++.la
libthc___la_LIBADD = $(LIBOBJS)
libthc___la_LDFLAGS = -release $(PACKAGE_VERSION) -version-info $(VERSION_INFO)
libthc___la_SOURCES = \
	rts/gc.cc

# test_interpreter_DEPENDENCIES = libthc.la
# test_interpreter_SOURCES = test/t_interpreter.cc
# test_interpreter_LDADD = libthc.la $(PTHREAD_LIBS) $(LIB_OBJS)

# clear out caches
maintainer-clean-local:
	rm -rf autom4te.cache aclocal.m4

# clean up after ourselves to the point where there is nothing left
super-clean: maintainer-clean-local
	rm -rf ./configure config.log config/config.h config.status Makefile Makefile.in

# darcs support
EXTRA_DIST = bin/autogen.sh
