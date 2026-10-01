#include <iostream>

int main() {
    static_assert(3.14159265358979323846 ==
                  3.141592653589793238462643383279502884,
                  "kPi literals are identical at double precision");
    std::cout << "kPi literals are identical at double precision" << std::endl;
    return 0;
}
