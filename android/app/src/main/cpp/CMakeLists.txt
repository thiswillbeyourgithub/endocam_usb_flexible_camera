# Builds libusb + libuvc (vendored, see third_party/VERSIONS.txt) statically
# into a single libendocam.so together with the JNI glue.
cmake_minimum_required(VERSION 3.22)
project(endocam C)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

add_library(usb STATIC
    ${TP}/libusb/libusb/core.c
    ${TP}/libusb/libusb/descriptor.c
    ${TP}/libusb/libusb/hotplug.c
    ${TP}/libusb/libusb/io.c
    ${TP}/libusb/libusb/sync.c
    ${TP}/libusb/libusb/strerror.c
    ${TP}/libusb/libusb/os/linux_usbfs.c
    ${TP}/libusb/libusb/os/events_posix.c
    ${TP}/libusb/libusb/os/threads_posix.c
    ${TP}/libusb/libusb/os/linux_netlink.c)
# android/config.h is libusb's own Android build configuration.
target_include_directories(usb PRIVATE ${TP}/libusb/android ${TP}/libusb/libusb/os
                           PUBLIC ${TP}/libusb/libusb)
target_compile_options(usb PRIVATE -pthread -Wno-unused-parameter)
target_link_libraries(usb PUBLIC log)

add_library(uvc STATIC
    ${TP}/libuvc/src/ctrl.c
    ${TP}/libuvc/src/ctrl-gen.c
    ${TP}/libuvc/src/device.c
    ${TP}/libuvc/src/diag.c
    ${TP}/libuvc/src/frame.c
    ${TP}/libuvc/src/init.c
    ${TP}/libuvc/src/misc.c
    ${TP}/libuvc/src/stream.c)
target_include_directories(uvc PUBLIC ${TP}/libuvc/include)
target_link_libraries(uvc PUBLIC usb)

add_library(endocam SHARED endocam_jni.c)
target_link_libraries(endocam uvc android jnigraphics log)
