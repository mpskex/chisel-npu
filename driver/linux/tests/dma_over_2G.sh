../tools/dma_to_device -a 0x7FFFFFFF -f ../tests/data/datafile_32M.bin -s 33554432 -v
../tools/dma_from_device -a 0x7FFFFFFF -f ../tests/data/datafile_32M.out.bin -s 33554432 -v
diff ../tests/data/datafile_32M.bin ../tests/data/datafile_32M.out.binm