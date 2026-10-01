#include <iostream>
#include "../dsp/lm_fitter.h"
#include "../dsp/lm_fitter.cpp"

using namespace winton::native;

int main() {
    bool allPass = true;
    if (!LmFitter::runToyCase()) allPass = false;
    if (!LmFitter::runMultiBandTest()) allPass = false;
    return allPass ? 0 : 1;
}
