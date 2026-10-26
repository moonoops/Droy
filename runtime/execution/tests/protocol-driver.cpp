#include "../protocol.hpp"
#include <iostream>
#include <iterator>
int main() {
    droy_exec::Bytes input(std::istreambuf_iterator<char>(std::cin), {});
    try {
        auto request = droy_exec::Start::parse(input);
        std::cout << request.host_uid << '\n' << request.source_workspace() << '\n'
                  << request.cwd << '\n' << request.argv.size() << '\n';
        return 0;
    } catch (const std::exception& error) {
        std::cout << error.what() << '\n'; return 2;
    }
}
