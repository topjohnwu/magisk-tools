#include <sys/types.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <sys/sysmacros.h>
#include <fcntl.h>
#include <pwd.h>
#include <unistd.h>
#include <syscall.h>
#include <random>
#include <string>

#include <utils.hpp>

using namespace std;

int fork_dont_care() {
    if (int pid = xfork()) {
        waitpid(pid, nullptr, 0);
        return pid;
    } else if (xfork()) {
        exit(0);
    }
    return 0;
}

int fork_no_orphan() {
    int pid = xfork();
    if (pid)
        return pid;
    prctl(PR_SET_PDEATHSIG, SIGTERM);
    if (getppid() == 1)
        exit(1);
    return 0;
}

constexpr char ALPHANUM[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
static bool seeded = false;
static std::mt19937 gen;
static std::uniform_int_distribution<int> dist(0, sizeof(ALPHANUM) - 2);
int gen_rand_str(char *buf, int len, bool varlen) {
    if (!seeded) {
        if (access("/dev/urandom", F_OK) != 0)
            mknod("/dev/urandom", 0600 | S_IFCHR, makedev(1, 9));
        int fd = xopen("/dev/urandom", O_RDONLY | O_CLOEXEC);
        unsigned seed;
        xxread(fd, &seed, sizeof(seed));
        gen.seed(seed);
        close(fd);
        seeded = true;
    }
    if (varlen) {
        std::uniform_int_distribution<int> len_dist(len / 2, len);
        len = len_dist(gen);
    }
    for (int i = 0; i < len - 1; ++i)
        buf[i] = ALPHANUM[dist(gen)];
    buf[len - 1] = '\0';
    return len - 1;
}

int exec_command(exec_t &exec) {
    int pipefd[] = {-1, -1};
    int outfd = -1;

    if (exec.fd == -1) {
        if (xpipe2(pipefd, O_CLOEXEC) == -1)
            return -1;
        outfd = pipefd[1];
    } else if (exec.fd >= 0) {
        outfd = exec.fd;
    }

    int pid = exec.fork();
    if (pid < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    } else if (pid) {
        if (exec.fd == -1) {
            exec.fd = pipefd[0];
            close(pipefd[1]);
        }
        return pid;
    }

    // Unblock all signals
    sigset_t set;
    sigfillset(&set);
    pthread_sigmask(SIG_UNBLOCK, &set, nullptr);

    if (outfd >= 0) {
        xdup2(outfd, STDOUT_FILENO);
        if (exec.err)
            xdup2(outfd, STDERR_FILENO);
        close(outfd);
    }

    // Call the pre-exec callback
    if (exec.pre_exec)
        exec.pre_exec();

    execve(exec.argv[0], (char **) exec.argv, environ);
    PLOGE("execve %s", exec.argv[0]);
    exit(-1);
}

int exec_command_sync(exec_t &exec) {
    int pid = exec_command(exec);
    if (pid < 0)
        return -1;
    int status;
    waitpid(pid, &status, 0);
    return WEXITSTATUS(status);
}

int new_daemon_thread(thread_entry entry, void *arg) {
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    return xpthread_create(&thread, &attr, entry, arg);
}

int new_daemon_thread(void(*entry)()) {
    thread_entry proxy = [](void *entry) -> void * {
        reinterpret_cast<void(*)()>(entry)();
        return nullptr;
    };
    return new_daemon_thread(proxy, (void *) entry);
}

int new_daemon_thread(std::function<void()> &&entry) {
    thread_entry proxy = [](void *fp) -> void * {
        auto fn = reinterpret_cast<std::function<void()>*>(fp);
        (*fn)();
        delete fn;
        return nullptr;
    };
    return new_daemon_thread(proxy, new std::function<void()>(std::move(entry)));
}

static char *argv0;
static size_t name_len;
void init_argv0(int argc, char **argv) {
    argv0 = argv[0];
    name_len = (argv[argc - 1] - argv[0]) + strlen(argv[argc - 1]) + 1;
}

void set_nice_name(const char *name) {
    memset(argv0, 0, name_len);
    strlcpy(argv0, name, name_len);
    prctl(PR_SET_NAME, name);
}

/*
 * Bionic's atoi runs through strtol().
 * Use our own implementation for faster conversion.
 */
int parse_int(const char *s) {
    int val = 0;
    char c;
    while ((c = *(s++))) {
        if (c > '9' || c < '0')
            return -1;
        val = val * 10 + c - '0';
    }
    return val;
}

uint32_t binary_gcd(uint32_t u, uint32_t v) {
    if (u == 0) return v;
    if (v == 0) return u;
    auto shift = __builtin_ctz(u | v);
    u >>= __builtin_ctz(u);
    do {
        v >>= __builtin_ctz(v);
        if (u > v) {
            auto t = v;
            v = u;
            u = t;
        }
        v -= u;
    } while (v != 0);
    return u << shift;
}

int switch_mnt_ns(int pid) {
    char mnt[32];
    snprintf(mnt, sizeof(mnt), "/proc/%d/ns/mnt", pid);
    if (access(mnt, R_OK) == -1) return 1; // Maybe process died..

    int fd, ret;
    fd = xopen(mnt, O_RDONLY);
    if (fd < 0) return 1;
    // Switch to its namespace
    ret = xsetns(fd, 0);
    close(fd);
    return ret;
}

string &replace_all(string &str, string_view from, string_view to) {
    size_t pos = 0;
    while((pos = str.find(from, pos)) != string::npos) {
        str.replace(pos, from.length(), to);
        pos += to.length();
    }
    return str;
}

vector<string> split(const string& s, const string& delimiters) {
    vector<string> result;
    size_t base = 0;
    size_t found;
    while (true) {
        found = s.find_first_of(delimiters, base);
        result.push_back(s.substr(base, found - base));
        if (found == string::npos) break;
        base = found + 1;
    }
    return result;
}
