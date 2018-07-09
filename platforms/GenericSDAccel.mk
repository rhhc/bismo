
SDACCEL_DSA ?= xilinx_kcu1500_dynamic_5_0
SDACCEL_OPTIMIZE ?= 3

SDACCEL_XML := $(TIDBITS_ROOT)/src/main/resources/xml/kernel_GenericSDAccelWrapperTop.xml
SDACCEL_XO := $(BUILD_DIR)/hw/bismo.xo
SDACCEL_XO_SCRIPT := $(TIDBITS_ROOT)/src/main/resources/script/gen_xo.tcl
SDACCEL_XCLBIN := $(BUILD_DIR)/hw/bismo.xclbin
SDACCEL_IP_SCRIPT := $(TIDBITS_ROOT)/src/main/resources/script/package_ip.tcl
SDACCEL_IP := $(BUILD_DIR)/hw/ip
EXTRA_VERILOG := $(BUILD_DIR_VERILOG)/GenericSDAccelWrapperTop.v
SDX_ENVVAR_SET := $(ls ${XILINX_SDX} 2> /dev/null)

.PHONY: sdaccelip xo xclbin check_sdx

check_sdx:
ifndef XILINX_SDX
    $(error "SDAccel environment variable XILINX_SDX not set properly")
endif

xo: $(SDACCEL_XO)
xclbin: $(SDACCEL_XCLBIN)
sdaccelip: $(SDACCEL_IP)

# TODO .v files should be platform-specific, ideally generated by fpga-tidbits
# for now we just copy everything from the fpga-tidbits extra verilog dir
$(EXTRA_VERILOG):
	cp $(TIDBITS_ROOT)/src/main/resources/verilog/*.v $(BUILD_DIR_VERILOG)

$(SDACCEL_IP): $(HW_VERILOG) $(EXTRA_VERILOG)
	vivado -mode batch -source $(SDACCEL_IP_SCRIPT) -tclargs GenericSDAccelWrapperTop $(BUILD_DIR_VERILOG) $(SDACCEL_IP)

$(SDACCEL_XO): $(SDACCEL_IP)
	vivado -mode batch -source $(SDACCEL_XO_SCRIPT) -tclargs $(SDACCEL_XO) GenericSDAccelWrapperTop $(SDACCEL_IP) $(SDACCEL_XML)

$(SDACCEL_XCLBIN): $(SDACCEL_XO)
	xocc --link --save-temps --target hw --kernel_frequency "0:$(FREQ_MHZ)|1:$(FREQ_MHZ)" --optimize $(SDACCEL_OPTIMIZE) --platform $(SDACCEL_DSA) $(SDACCEL_XO) -o $(SDACCEL_XCLBIN)

hw: $(SDACCEL_XCLBIN)
	cp $(SDACCEL_XCLBIN) $(BUILD_DIR_DEPLOY)/BitSerialMatMulAccel

sw: $(BUILD_DIR_HWDRV)/BitSerialMatMulAccel.hpp
	cp -r $(APP_SRC_DIR)/* $(BUILD_DIR_DEPLOY)/;
	cd $(BUILD_DIR_DEPLOY)/;
	g++ -std=c++11 -DCSR_BASE_ADDR=0x1800000  -DFCLK_MHZ=$(FREQ_MHZ) -I$(XILINX_SDX)/runtime/driver/include  -L$(XILINX_SDX)/platforms/$(SDACCEL_DSA)/sw/driver/gem -L$(XILINX_SDX)/runtime/lib/x86_64 -lxilinxopencl -lxclgemdrv -lpthread -lrt -lstdc++ *.cpp -o $(BUILD_DIR_DEPLOY)/bismo

run:
	LD_LIBRARY_PATH=$(LD_LIBRARY_PATH):$(XILINX_SDX)/runtime/lib/x86_64:$(XILINX_SDX)/platforms/$(SDACCEL_DSA)/sw/driver/gem $(BUILD_DIR_DEPLOY)/bismo