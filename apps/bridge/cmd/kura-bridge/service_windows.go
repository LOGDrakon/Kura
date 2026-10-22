//go:build windows

package main

import (
	"context"
	"flag"
	"log/slog"
	"os"
	"path/filepath"

	"golang.org/x/sys/windows/svc"

	"github.com/LOGDrakon/Kura/apps/bridge/internal/config"
)

// Lancé par le gestionnaire de services Windows (sc.exe create … binPath= "kura-bridge.exe -c C:\Kura\kura-bridge.yaml").
func init() {
	if ok, err := svc.IsWindowsService(); err == nil && ok {
		runAsService = serviceMain
	}
}

func serviceMain(args []string) error {
	fs := flag.NewFlagSet("service", flag.ContinueOnError)
	path := configFlag(fs)
	fs.Parse(args)
	p := *path
	if !filepath.IsAbs(p) {
		// Un service démarre dans C:\Windows\System32 : le chemin relatif se rapporte au dossier de l'exécutable.
		if exe, err := os.Executable(); err == nil {
			p = filepath.Join(filepath.Dir(exe), p)
		}
	}
	c, err := config.Load(p)
	if err != nil {
		return err
	}
	return svc.Run("KuraBridge", &service{c: c, log: newLogger(c)})
}

type service struct {
	c   *config.Config
	log *slog.Logger
}

func (s *service) Execute(_ []string, req <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	status <- svc.Status{State: svc.StartPending}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- serve(ctx, s.c, s.log) }()
	status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for {
		select {
		case err := <-done:
			cancel()
			if err != nil {
				s.log.Error("arrêt sur erreur", "err", err)
				return false, 1
			}
			return false, 0
		case r := <-req:
			switch r.Cmd {
			case svc.Interrogate:
				status <- r.CurrentStatus
			case svc.Stop, svc.Shutdown:
				status <- svc.Status{State: svc.StopPending}
				cancel()
				<-done
				return false, 0
			}
		}
	}
}
