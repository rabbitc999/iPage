# ipage C++ 版（无 CMake 时的兜底构建）
#
# 用法：
#   make            # 构建示例与测试，并跑测试
#   make test       # 只跑测试
#   make clean
#
# 需要 g++ / gcc（C++17）。

# 有些 MinGW 环境没有 `cc` 别名，这里显式指定。
CXX      := $(shell command -v g++ 2>/dev/null || echo g++)
CC       := $(shell command -v gcc 2>/dev/null || echo $(CXX))
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra
CFLAGS   ?= -O2 -DSQLITE_THREADSAFE=1 -DSQLITE_OMIT_LOAD_EXTENSION -w
INCLUDES  = -I. -Isqlite

BUILD    = build
LIB_SRC  = ipage/dialect.cpp ipage/sql.cpp ipage/connection.cpp
LIB_OBJ  = $(patsubst ipage/%.cpp,$(BUILD)/%.o,$(LIB_SRC))
SQLITE_O = $(BUILD)/sqlite3.o

.PHONY: all test clean

all: $(BUILD)/example $(BUILD)/test_ipage
	@$(BUILD)/test_ipage

$(BUILD):
	@mkdir -p $(BUILD)

$(BUILD)/%.o: ipage/%.cpp | $(BUILD)
	$(CXX) $(CXXFLAGS) $(INCLUDES) -c $< -o $@

$(SQLITE_O): sqlite/sqlite3.c | $(BUILD)
	$(CC) $(CFLAGS) -Isqlite -c $< -o $@

$(BUILD)/test_ipage: tests/test_ipage.cpp $(LIB_OBJ) $(SQLITE_O) | $(BUILD)
	$(CXX) $(CXXFLAGS) $(INCLUDES) $< $(LIB_OBJ) $(SQLITE_O) -o $@

$(BUILD)/example: examples/main.cpp $(LIB_OBJ) $(SQLITE_O) | $(BUILD)
	$(CXX) $(CXXFLAGS) $(INCLUDES) $< $(LIB_OBJ) $(SQLITE_O) -o $@

test: $(BUILD)/test_ipage
	@$(BUILD)/test_ipage

clean:
	@rm -rf $(BUILD)
